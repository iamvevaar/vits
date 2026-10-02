package com.vits.engine.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import com.vits.audio.Resampler
import com.vits.audio.WsolaTimeStretcher
import com.vits.engine.AssetResolver
import com.vits.project.Clip
import com.vits.project.Project
import java.nio.ByteOrder
import kotlin.math.roundToLong

/** One encoded AAC access unit, held until the muxer interleaves it with video. */
internal class EncodedSample(val data: ByteArray, val ptsUs: Long, val flags: Int)

internal class EncodedAudio(val format: MediaFormat, val samples: List<EncodedSample>)

/** Thrown by [Exporter.run] after [Exporter.cancel]; the partial output has been deleted. */
class ExportCancelledException : RuntimeException("Export cancelled")

/**
 * Renders the project's soundtrack to AAC: for every clip, decode its source span, retime it with
 * the clip's own speed map (pitch preserved), apply volume, convert to [SAMPLE_RATE] stereo, and
 * place it at a sample-exact offset. Clip boundaries are computed from cumulative timeline time,
 * so rounding never accumulates into drift across many cuts. Clips without audio become silence.
 */
internal class AudioMixdown(
    private val project: Project,
    private val resolver: AssetResolver,
    private val cancelled: () -> Boolean,
    private val onProgress: (Float) -> Unit,
) {
    private val pending = FloatFifo()
    private lateinit var encoder: MediaCodec
    private val samples = ArrayList<EncodedSample>()
    private var outFormat: MediaFormat? = null
    private var queuedFrames = 0L
    private var encodeDone = false
    private val info = MediaCodec.BufferInfo()
    /** Encoder+decoder latency; packets are stamped this much earlier so sound lands on time. */
    private var delayUs = 0L

    /** Returns null when no clip has audio: the export then has no audio track at all. */
    fun run(): EncodedAudio? {
        val clips = project.main.clips
        if (clips.none { project.asset(it.assetId).hasAudio }) return null
        val delay = AacDelay.samples(::createAacEncoder, SAMPLE_RATE, CHANNELS)
        delayUs = delay * 1_000_000L / SAMPLE_RATE
        encoder = createAacEncoder()
        try {
            var startUs = 0L
            for ((i, clip) in clips.withIndex()) {
                val endUs = startUs + clip.durationUs
                val frames = frameAt(endUs) - frameAt(startUs)
                val written = if (project.asset(clip.assetId).hasAudio) decodeClip(clip, frames) else 0L
                if (written < frames) pushSilence(frames - written)
                startUs = endUs
                onProgress((i + 1f) / clips.size)
            }
            while (!encodeDone) pump(endOfStream = true)
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
        }
        return outFormat?.let { EncodedAudio(it, samples) }
    }

    /** Decodes, retimes and resamples [clip]; writes at most [frames] output frames. */
    private fun decodeClip(clip: Clip, frames: Long): Long {
        val asset = project.asset(clip.assetId)
        val extractor = resolver.open(asset).newExtractor().apply { selectTrack(asset.audioTrack) }
        val format = extractor.getTrackFormat(asset.audioTrack)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0)
        decoder.start()
        // Start early: a decoder's first frame after a seek is a warm-up, so let it fall in the part
        // that is discarded before the in-point.
        extractor.seekTo(asset.originUs + maxOf(0, clip.sourceStartUs - DECODER_PREROLL_US), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        var written = 0L
        var stretcher: WsolaTimeStretcher? = null
        var resampler: Resampler? = null
        var inRate = 0
        var inChannels = 0
        var sourceFrames = 0L       // frames fed to the stretcher, counted from clip.sourceStartUs
        var sourceLimit = 0L
        var started = false
        var inputDone = false
        var outputDone = false
        var shorts = ShortArray(0)
        val gain = clip.volume

        // Raised-cosine ramps at both clip edges (a hard splice between waveforms clicks). The last
        // FADE_FRAMES are held back so the fade-out ends where the sound really ends, even when the
        // stretcher finishes a few samples short of the planned length.
        val tail = FloatArray(FADE_FRAMES * CHANNELS)
        var tailFrames = 0
        fun emit(pcm: FloatArray) {
            val n = minOf(((frames - written) * CHANNELS).toInt(), pcm.size) / CHANNELS
            if (n <= 0) return
            for (j in 0 until minOf(n, (FADE_FRAMES - written).toInt().coerceAtLeast(0))) {
                val g = raisedCosine(written + j)
                for (c in 0 until CHANNELS) pcm[j * CHANNELS + c] *= g
            }
            written += n
            // Combined stream = tail + pcm[0 until n]; everything but the last FADE_FRAMES goes out.
            val total = tailFrames + n
            val release = total - FADE_FRAMES
            if (release > 0) {
                val fromTail = minOf(release, tailFrames)
                pending.push(tail, fromTail * CHANNELS)
                tail.copyInto(tail, 0, fromTail * CHANNELS, tailFrames * CHANNELS)
                tailFrames -= fromTail
                val fromPcm = release - fromTail
                if (fromPcm > 0) pending.push(pcm, fromPcm * CHANNELS)
                pcm.copyInto(tail, tailFrames * CHANNELS, fromPcm * CHANNELS, n * CHANNELS)
                tailFrames += n - fromPcm
            } else {
                pcm.copyInto(tail, tailFrames * CHANNELS, 0, n * CHANNELS)
                tailFrames += n
            }
        }
        fun flushTail() {
            for (j in 0 until tailFrames) {
                val g = raisedCosine((tailFrames - 1 - j).toLong())
                for (c in 0 until CHANNELS) tail[j * CHANNELS + c] *= g
            }
            pending.push(tail, tailFrames * CHANNELS)
            tailFrames = 0
        }

        try {
            while (!outputDone && written < frames) {
                if (cancelled()) throw ExportCancelledException()
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
                val o = decoder.dequeueOutputBuffer(info, 1_000)
                if (stretcher == null && (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || o >= 0)) {
                    val f = decoder.outputFormat
                    inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    inChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    // Read a little past the out-point: the stretcher emits whole hops and may otherwise
                    // fall short of the planned length; output is capped at exactly `frames` anyway.
                    val overrun = minOf(OVERRUN_US, asset.durationUs - clip.sourceEndUs).coerceAtLeast(0)
                    sourceLimit = (clip.sourceDurationUs + overrun) * inRate / 1_000_000
                    val rate = inRate
                    val map = clip.timeMap
                    val rs = Resampler(inRate, SAMPLE_RATE, CHANNELS).also { resampler = it }
                    stretcher = WsolaTimeStretcher(rate, CHANNELS, { frame -> map.speedAtSource(frame * 1_000_000 / rate) }) { pcm, n ->
                        emit(rs.process(FloatArray(n * CHANNELS) { pcm[it] / 32768f }, n))
                    }
                }
                if (o >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val buf = decoder.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset).limit(info.offset + info.size)
                    val sb = buf.asShortBuffer()
                    val count = sb.remaining() / inChannels
                    if (shorts.size < count * inChannels) shorts = ShortArray(count * inChannels)
                    sb.get(shorts, 0, count * inChannels)
                    decoder.releaseOutputBuffer(o, false)

                    val s = stretcher!!
                    var first = 0
                    if (!started && count > 0) {
                        // Align to the in-point: drop audio before it, or pad silence if it starts late.
                        val delta = ((info.presentationTimeUs - asset.originUs - clip.sourceStartUs) * inRate / 1_000_000)
                        if (delta > 0) {
                            s.queue(ShortArray((delta * CHANNELS).toInt()), delta.toInt())
                            sourceFrames += delta
                        } else {
                            first = minOf(count.toLong(), -delta).toInt()
                        }
                        started = first < count || delta > 0
                    }
                    val take = minOf((count - first).toLong(), sourceLimit - sourceFrames).toInt()
                    if (take > 0) {
                        s.queue(toStereo(shorts, first, take, inChannels, gain), take)
                        sourceFrames += take
                    }
                    if (eos || sourceFrames >= sourceLimit) {
                        s.finish()
                        emit(resampler!!.finish())
                        outputDone = true
                    }
                }
                pump(endOfStream = false)
            }
            flushTail()
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            extractor.release()
        }
        return written
    }

    private fun pushSilence(frames: Long) {
        var left = frames
        val chunk = FloatArray(4096 * CHANNELS)
        while (left > 0) {
            val n = minOf(left, 4096L).toInt()
            pending.push(chunk, n * CHANNELS)
            left -= n
            pump(endOfStream = false)
        }
    }

    /** Moves pending PCM into the encoder and collects its output. */
    private fun pump(endOfStream: Boolean) {
        val totalFrames = frameAt(project.durationUs)
        // Feed every buffer the encoder will take: slow motion multiplies audio ~10× at 0.1x,
        // so one buffer per pass would let the pending queue grow without bound.
        while (queuedFrames < totalFrames && (endOfStream || pending.size >= ENCODE_CHUNK * CHANNELS)) {
            val i = encoder.dequeueInputBuffer(if (endOfStream) 10_000 else 0)
            if (i < 0) break
            run {
                val buf = encoder.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
                val want = minOf((buf.capacity() / 2 / CHANNELS).toLong(), totalFrames - queuedFrames).toInt()
                if (endOfStream) pending.padSilence(want * CHANNELS)
                val n = minOf(want, pending.size / CHANNELS)
                pending.popInto(buf.asShortBuffer(), n * CHANNELS)
                val pts = queuedFrames * 1_000_000 / SAMPLE_RATE
                queuedFrames += n
                val eos = queuedFrames >= totalFrames
                encoder.queueInputBuffer(i, 0, n * CHANNELS * 2, pts, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            }
        }
        while (true) {
            val o = encoder.dequeueOutputBuffer(info, if (endOfStream) 1_000 else 0)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outFormat = encoder.outputFormat
                continue
            }
            if (o < 0) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val b = encoder.getOutputBuffer(o)!!
                b.position(info.offset).limit(info.offset + info.size)
                // Packets that only carry priming land before zero and are dropped.
                val pts = info.presentationTimeUs - delayUs
                if (pts >= 0) samples += EncodedSample(ByteArray(info.size).also { b.get(it) }, pts, info.flags)
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encodeDone = true
            encoder.releaseOutputBuffer(o, false)
        }
    }

    /** 0 at the edge rising to 1 over FADE_FRAMES samples. */
    private fun raisedCosine(fromEdge: Long): Float =
        if (fromEdge >= FADE_FRAMES) 1f else (0.5 - 0.5 * kotlin.math.cos(kotlin.math.PI * fromEdge / FADE_FRAMES)).toFloat()

    private fun frameAt(timelineUs: Long): Long = (timelineUs * SAMPLE_RATE / 1e6).roundToLong()

    private fun createAacEncoder(): MediaCodec {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    /** Mono is duplicated, multichannel keeps front left/right; [gain] applied with clipping. */
    private fun toStereo(src: ShortArray, startFrame: Int, frames: Int, inCh: Int, gain: Float): ShortArray {
        val out = ShortArray(frames * 2)
        for (f in 0 until frames) {
            val base = (startFrame + f) * inCh
            val l = src[base] * gain
            val r = (if (inCh == 1) src[base] else src[base + 1]) * gain
            out[f * 2] = l.toInt().coerceIn(-32768, 32767).toShort()
            out[f * 2 + 1] = r.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val ENCODE_CHUNK = 1024
        const val FADE_FRAMES = SAMPLE_RATE * 4 / 1000
        const val DECODER_PREROLL_US = 200_000L
        const val OVERRUN_US = 60_000L
    }
}

/** Growable FIFO of interleaved float PCM, drained as 16-bit into encoder buffers. */
private class FloatFifo {
    private var data = FloatArray(1 shl 15)
    private var head = 0
    var size = 0
        private set

    fun push(src: FloatArray, count: Int) {
        ensure(count)
        src.copyInto(data, head + size, 0, count)
        size += count
    }

    fun padSilence(total: Int) {
        if (size >= total) return
        val add = total - size
        ensure(add)
        data.fill(0f, head + size, head + size + add)
        size += add
    }

    fun popInto(dst: java.nio.ShortBuffer, count: Int) {
        for (i in 0 until count) dst.put((data[head + i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        head += count
        size -= count
    }

    private fun ensure(extra: Int) {
        if (head + size + extra <= data.size) return
        val cap = maxOf(data.size, (size + extra) * 2)
        val next = if (cap > data.size) FloatArray(cap) else data
        data.copyInto(next, 0, head, head + size)
        data = next
        head = 0
    }
}
