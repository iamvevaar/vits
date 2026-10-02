package com.vits.engine.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.vits.audio.WsolaTimeStretcher
import com.vits.engine.MediaInfo
import com.vits.engine.MediaInput
import com.vits.timeline.TimeMap
import java.nio.ByteOrder

/** One encoded AAC access unit, held until the muxer interleaves it with video. */
internal class EncodedSample(val data: ByteArray, val ptsUs: Long, val flags: Int)

internal class EncodedAudio(val format: MediaFormat, val samples: List<EncodedSample>)

/**
 * Decode → pitch-preserving retime (same TimeMap as the video) → AAC. Output is trimmed or padded
 * to exactly the retimed duration so audio and video end together.
 */
internal class AudioRetimer(
    private val input: MediaInput,
    private val info: MediaInfo,
    private val map: TimeMap,
    private val cancelled: () -> Boolean,
    private val onProgress: (Float) -> Unit,
) {
    fun run(): EncodedAudio? {
        if (!info.hasAudio) return null
        val extractor = input.newExtractor().apply { selectTrack(info.audioTrack) }
        val inFormat = extractor.getTrackFormat(info.audioTrack)
        val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inFormat, null, null, 0)
        decoder.start()

        var encoder: MediaCodec? = null
        var stretcher: WsolaTimeStretcher? = null
        var sampleRate = 0
        var inChannels = 0
        var outChannels = 0
        val pending = ShortFifo()
        val samples = ArrayList<EncodedSample>()
        var outFormat: MediaFormat? = null
        var targetFrames = 0L
        var queuedFrames = 0L
        var sourceFrames = 0L         // PCM frames handed to the stretcher (source timeline)
        var sourceLimit = 0L
        var leadingSkip = 0L          // frames before the video's first frame to drop
        var inputDone = false
        var decodeDone = false
        var encodeInputDone = false
        var encodeDone = false
        val info0 = MediaCodec.BufferInfo()
        var scratch = ShortArray(0)

        extractor.seekTo(info.originUs, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        try {
            while (!encodeDone) {
                if (cancelled()) throw ExportCancelled()
                // 1. Feed compressed audio.
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(0)
                    if (i >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                // 2. Decoded PCM → stretcher → pending.
                if (!decodeDone) {
                    val o = decoder.dequeueOutputBuffer(info0, 1_000)
                    if (encoder == null && (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || o >= 0)) {
                        val f = decoder.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        inChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        outChannels = minOf(inChannels, 2)
                        targetFrames = map.outputDurationUs * sampleRate / 1_000_000
                        sourceLimit = info.durationUs * sampleRate / 1_000_000
                        val sr = sampleRate
                        stretcher = WsolaTimeStretcher(sr, outChannels, { frame ->
                            map.speedAtSource(frame * 1_000_000 / sr)
                        }) { pcm, frames -> pending.push(pcm, frames * outChannels) }
                        encoder = createAacEncoder(sampleRate, outChannels)
                    }
                    if (o >= 0) {
                        val buf = decoder.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info0.offset).limit(info0.offset + info0.size)
                        val shorts = buf.asShortBuffer()
                        var frames = shorts.remaining() / inChannels
                        val s = stretcher!!
                        if (sourceFrames == 0L && leadingSkip == 0L) {
                            // Align to the video's first frame: drop audio before it, pad silence after.
                            val delta = (info0.presentationTimeUs - info.originUs) * sampleRate / 1_000_000
                            if (delta > 0) {
                                val silence = ShortArray((delta * outChannels).toInt())
                                s.queue(silence, delta.toInt())
                                sourceFrames += delta
                            } else {
                                leadingSkip = -delta
                            }
                        }
                        if (scratch.size < frames * inChannels) scratch = ShortArray(frames * inChannels)
                        shorts.get(scratch, 0, frames * inChannels)
                        var start = 0
                        if (leadingSkip > 0) {
                            val skip = minOf(leadingSkip, frames.toLong()).toInt()
                            leadingSkip -= skip
                            start = skip
                        }
                        frames = minOf((frames - start).toLong(), sourceLimit - sourceFrames).toInt().coerceAtLeast(0)
                        if (frames > 0) {
                            val mixed = downmix(scratch, start, frames, inChannels, outChannels)
                            s.queue(mixed, frames)
                            sourceFrames += frames
                            onProgress(sourceFrames.toFloat() / sourceLimit)
                        }
                        decoder.releaseOutputBuffer(o, false)
                        if (info0.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || sourceFrames >= sourceLimit) {
                            s.finish()
                            decodeDone = true
                        }
                    }
                }
                val enc = encoder ?: continue
                // 3. Pending PCM → AAC encoder, exactly targetFrames long.
                if (!encodeInputDone && (decodeDone || pending.size >= ENCODE_CHUNK * outChannels)) {
                    val i = enc.dequeueInputBuffer(0)
                    if (i >= 0) {
                        val buf = enc.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
                        val want = minOf((buf.capacity() / 2 / outChannels).toLong(), targetFrames - queuedFrames)
                            .toInt().coerceAtLeast(0)
                        // Once the source is exhausted, pad with silence up to the exact target length.
                        if (decodeDone) pending.padSilence(want * outChannels)
                        val frames = minOf(want, pending.size / outChannels)
                        pending.popInto(buf.asShortBuffer(), frames * outChannels)
                        val pts = queuedFrames * 1_000_000 / sampleRate
                        queuedFrames += frames
                        val eos = queuedFrames >= targetFrames
                        enc.queueInputBuffer(i, 0, frames * outChannels * 2, pts,
                            if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (eos) encodeInputDone = true
                    }
                }
                // 4. Collect AAC.
                val o = enc.dequeueOutputBuffer(info0, 1_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = enc.outputFormat
                } else if (o >= 0) {
                    if (info0.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info0.size > 0) {
                        val buf = enc.getOutputBuffer(o)!!
                        buf.position(info0.offset).limit(info0.offset + info0.size)
                        val bytes = ByteArray(info0.size).also { buf.get(it) }
                        samples += EncodedSample(bytes, info0.presentationTimeUs, info0.flags)
                    }
                    if (info0.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encodeDone = true
                    enc.releaseOutputBuffer(o, false)
                }
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            encoder?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
        return outFormat?.let { EncodedAudio(it, samples) }
    }

    private fun createAacEncoder(sampleRate: Int, channels: Int): MediaCodec {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 96_000 else 192_000)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    /** Copies [frames] from [src] (offset in frames) reducing >2 channels to stereo. */
    private fun downmix(src: ShortArray, startFrame: Int, frames: Int, inCh: Int, outCh: Int): ShortArray {
        val out = ShortArray(frames * outCh)
        if (inCh == outCh) {
            src.copyInto(out, 0, startFrame * inCh, (startFrame + frames) * inCh)
            return out
        }
        for (f in 0 until frames) {
            val base = (startFrame + f) * inCh
            // Front left/right only: simple and never clips; fine for phone-recorded multichannel.
            out[f * 2] = src[base]
            out[f * 2 + 1] = src[base + 1]
        }
        return out
    }
}

/** Growable FIFO of interleaved PCM shorts. */
private const val ENCODE_CHUNK = 1024

private class ShortFifo {
    private var data = ShortArray(1 shl 15)
    private var head = 0
    var size = 0
        private set

    fun push(src: ShortArray, count: Int) {
        ensure(count)
        src.copyInto(data, head + size, 0, count)
        size += count
    }

    fun padSilence(total: Int) {
        if (size >= total) return
        val add = total - size
        ensure(add)
        data.fill(0, head + size, head + size + add)
        size += add
    }

    fun popInto(dst: java.nio.ShortBuffer, count: Int) {
        dst.put(data, head, count)
        head += count
        size -= count
    }

    private fun ensure(extra: Int) {
        if (head + size + extra <= data.size) return
        val cap = maxOf(data.size, (size + extra) * 2)
        val next = if (cap > data.size) ShortArray(cap) else data
        data.copyInto(next, 0, head, head + size)
        data = next
        head = 0
    }
}

internal class ExportCancelled : RuntimeException("Export cancelled")
