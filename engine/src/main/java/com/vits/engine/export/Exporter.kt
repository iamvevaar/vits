package com.vits.engine.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glViewport
import android.os.Looper
import android.util.Log
import com.vits.engine.MediaInfo
import com.vits.engine.MediaInput
import com.vits.engine.SmoothMode
import com.vits.engine.gl.EglCore
import com.vits.engine.gl.IDENTITY
import com.vits.engine.interp.FlowQuality
import com.vits.engine.render.TimelineRenderer
import com.vits.timeline.SpeedSpec
import com.vits.timeline.TimeMap
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class ExportRequest(
    val input: MediaInput,
    val info: MediaInfo,
    val speed: SpeedSpec,
    val smoothMode: SmoothMode,
    val output: File,
    /** Output frame rate; defaults to the source's. Higher values make slow-mo even smoother. */
    val fps: Int = info.nominalFps,
    /** Overrides the automatic bitrate (bits/s); used by benchmarks to keep codec loss negligible. */
    val bitrate: Int? = null,
)

/**
 * Renders a retimed clip to MP4 (H.264 + AAC). Blocking; run it on a dedicated HandlerThread
 * because it binds a GL context to the calling thread and needs a Looper. Throws on failure; returns normally on success.
 */
class Exporter(private val request: ExportRequest) {
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun run(onProgress: (Float) -> Unit) {
        // Some codec stacks (seen on goldfish/Codec2) never emit decoded frames to a SurfaceTexture
        // owned by a thread without a Looper; fail fast instead of stalling.
        checkNotNull(Looper.myLooper()) { "Exporter.run must be called on a Looper thread (e.g. HandlerThread)" }
        val r = request
        val map = TimeMap(r.speed, r.info.durationUs)
        val audio = AudioRetimer(r.input, r.info, map, { cancelled }) { onProgress(it * AUDIO_SHARE) }.run()

        val (encWidth, encHeight) = encoderSize(r.info.width, r.info.height)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_FRAME_RATE, r.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        // Pick the encoder before setting bitrate: an out-of-range bitrate makes the lookup fail.
        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: error("No H.264 encoder for ${encWidth}x$encHeight")
        val encoder = MediaCodec.createByCodecName(codecName)
        val bitrateRange = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .videoCapabilities.bitrateRange
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateRange.clamp(r.bitrate ?: bitrate(encWidth, encHeight, r.fps)))
        if (supportsHighProfile(encoder)) {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        }
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: MediaCodec.CodecException) {
            // Some vendor encoders advertise High but refuse it; the default profile always works.
            format.removeKey(MediaFormat.KEY_PROFILE)
            encoder.reset()
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        Log.d(TAG, "encoder $codecName ${encWidth}x$encHeight @${r.fps} audio=${audio?.samples?.size}")
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        r.output.delete()
        val muxer = MediaMuxer(r.output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(r.info.rotation)
        val writer = InterleavingWriter(muxer, audio)

        val egl = EglCore()
        val eglSurface = egl.createWindowSurface(inputSurface)
        egl.makeCurrent(eglSurface)
        var renderer: TimelineRenderer? = null
        var success = false
        try {
            renderer = TimelineRenderer(r.input, r.info, FlowQuality.EXPORT)
            val frameCount = max(1L, (map.outputDurationUs * r.fps / 1_000_000.0).roundToLong())
            val bufferInfo = MediaCodec.BufferInfo()
            for (k in 0 until frameCount) {
                if (cancelled) throw ExportCancelled()
                val outUs = k * 1_000_000 / r.fps
                val src = map.sourceTimeAt(outUs)
                renderer.render(src, map.speedAtSource(src), r.smoothMode, IDENTITY) {
                    glBindFramebuffer(GL_FRAMEBUFFER, 0)
                    glViewport(0, 0, encWidth, encHeight)
                }
                egl.setPresentationTime(eglSurface, outUs * 1000)
                egl.swapBuffers(eglSurface)
                if (k % 30 == 0L) Log.d(TAG, "frame $k/$frameCount src=$src")
                drain(encoder, bufferInfo, writer, endOfStream = false)
                onProgress(AUDIO_SHARE + (1 - AUDIO_SHARE) * (k + 1) / frameCount)
            }
            encoder.signalEndOfInputStream()
            drain(encoder, bufferInfo, writer, endOfStream = true)
            writer.finish()
            success = true
        } finally {
            renderer?.close()
            egl.releaseSurface(eglSurface)
            egl.release()
            runCatching { encoder.stop() }
            encoder.release()
            inputSurface.release()
            runCatching { if (writer.started) muxer.stop() }
            muxer.release()
            if (!success) r.output.delete()
        }
    }

    private fun drain(encoder: MediaCodec, info: MediaCodec.BufferInfo, writer: InterleavingWriter, endOfStream: Boolean) {
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> writer.start(encoder.outputFormat)
                index >= 0 -> {
                    val buf = encoder.getOutputBuffer(index)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        buf.position(info.offset).limit(info.offset + info.size)
                        writer.writeVideo(buf, info)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Largest size ≤ the source that the encoder accepts, preserving aspect ratio. */
    private fun encoderSize(width: Int, height: Int): Pair<Int, Int> {
        val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
            .map { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }
        var w = width
        var h = height
        repeat(8) {
            val ew = w and 1.inv()
            val eh = h and 1.inv()
            if (caps.any { it.isSizeSupported(ew, eh) }) return ew to eh
            w = (w * 0.85f).roundToInt()
            h = (h * 0.85f).roundToInt()
        }
        val scale = 1920f / max(width, height)
        return ((width * scale).roundToInt() and 1.inv()) to ((height * scale).roundToInt() and 1.inv())
    }

    private fun bitrate(w: Int, h: Int, fps: Int): Int =
        (w.toDouble() * h * fps * BITS_PER_PIXEL).toLong().coerceIn(2_000_000L, 60_000_000L).toInt()

    private fun supportsHighProfile(encoder: MediaCodec): Boolean =
        encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).profileLevels
            .any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }

    private companion object {
        const val TAG = "VitsExport"
        const val AUDIO_SHARE = 0.1f
        const val BITS_PER_PIXEL = 0.14
    }
}

/** Writes video as it is encoded and slots pre-encoded audio in by timestamp. */
private class InterleavingWriter(private val muxer: MediaMuxer, private val audio: EncodedAudio?) {
    private var videoTrack = -1
    private var audioTrack = -1
    private var audioIndex = 0
    private val audioInfo = MediaCodec.BufferInfo()
    var started = false
        private set

    fun start(videoFormat: MediaFormat) {
        check(!started) { "video format changed twice" }
        videoTrack = muxer.addTrack(videoFormat)
        if (audio != null) audioTrack = muxer.addTrack(audio.format)
        muxer.start()
        started = true
    }

    fun writeVideo(buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        check(started)
        writeAudioUpTo(info.presentationTimeUs)
        muxer.writeSampleData(videoTrack, buf, info)
    }

    fun finish() {
        if (started) writeAudioUpTo(Long.MAX_VALUE)
    }

    private fun writeAudioUpTo(ptsUs: Long) {
        val samples = audio?.samples ?: return
        while (audioIndex < samples.size && samples[audioIndex].ptsUs <= ptsUs) {
            val s = samples[audioIndex++]
            audioInfo.set(0, s.data.size, s.ptsUs, s.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
            muxer.writeSampleData(audioTrack, java.nio.ByteBuffer.wrap(s.data), audioInfo)
        }
    }
}
