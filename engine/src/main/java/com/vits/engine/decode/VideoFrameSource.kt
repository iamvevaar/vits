package com.vits.engine.decode

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.vits.engine.MediaInfo
import com.vits.engine.MediaInput
import java.io.Closeable

/**
 * Hardware decoder writing straight into a GL external texture: decoded frames never touch the
 * CPU. Frames are produced in presentation order; [next] latches one into [oesTexture].
 * Must be used from the thread that owns the GL context.
 */
internal class VideoFrameSource(
    input: MediaInput,
    private val info: MediaInfo,
    val oesTexture: Int,
) : Closeable {
    private val extractor: MediaExtractor = input.newExtractor().apply { selectTrack(info.videoTrack) }
    private val callbackThread = HandlerThread("vits-frame-cb").apply { start() }
    private val surfaceTexture = SurfaceTexture(oesTexture)
    private val surface: Surface
    private val format: MediaFormat
    private var decoder: MediaCodec
    private var lastReturnedUs = Long.MIN_VALUE
    private val bufferInfo = MediaCodec.BufferInfo()
    private val lock = Object()
    private var frameAvailable = false

    private var inputDone = false
    private var outputDone = false
    private var skipBeforeUs = Long.MIN_VALUE

    /** SurfaceTexture transform (crop + flip) for the latched frame. */
    val texMatrix = FloatArray(16)

    init {
        surfaceTexture.setOnFrameAvailableListener({
            synchronized(lock) {
                frameAvailable = true
                lock.notifyAll()
            }
        }, Handler(callbackThread.looper))
        surface = Surface(surfaceTexture)
        format = extractor.getTrackFormat(info.videoTrack)
        // Keep frames in coded orientation; rotation is applied at display/mux time instead.
        format.setInteger(MediaFormat.KEY_ROTATION, 0)
        decoder = createDecoder()
    }

    private fun createDecoder(): MediaCodec =
        MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
            configure(format, surface, null, 0)
            start()
            Log.d(TAG, "decoder $name for ${format.getString(MediaFormat.KEY_MIME)}")
        }

    /** Repositions so the next frames returned lead up to [sourceUs] (skipping far-earlier ones). */
    fun seekTo(sourceUs: Long) {
        Log.d(TAG, "seek $sourceUs")
        decoder.flush()
        extractor.seekTo(info.originUs + sourceUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputDone = false
        outputDone = false
        skipBeforeUs = sourceUs - SKIP_MARGIN_US
        lastReturnedUs = Long.MIN_VALUE
        synchronized(lock) { frameAvailable = false }
    }

    /**
     * Decodes the next frame into [oesTexture]. Returns its source time, or null at end of stream.
     * A decoder that wedges is replaced once, resuming right after the last delivered frame.
     */
    fun next(): Long? = try {
        decodeNext()
    } catch (e: DecoderStalledException) {
        Log.w(TAG, "decoder stalled; recreating", e)
        recover()
        decodeNext()
    }

    private fun recover() {
        runCatching { decoder.stop() }
        decoder.release()
        decoder = createDecoder()
        val resumeAt = if (lastReturnedUs == Long.MIN_VALUE) skipBeforeUs + SKIP_MARGIN_US else lastReturnedUs
        extractor.seekTo(info.originUs + maxOf(0, resumeAt), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputDone = false
        outputDone = false
        if (lastReturnedUs != Long.MIN_VALUE) skipBeforeUs = lastReturnedUs + 1
        synchronized(lock) { frameAvailable = false }
    }

    private fun decodeNext(): Long? {
        var idleSince = System.nanoTime()
        while (!outputDone) {
            if (!inputDone) feedInput()
            val index = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
            if (index < 0) {
                // A wedged codec must fail loudly rather than hang the pipeline forever.
                if (System.nanoTime() - idleSince > STALL_TIMEOUT_NS) {
                    throw DecoderStalledException("Video decoder stalled (${decoder.name}, inputDone=$inputDone)")
                }
                continue
            }
            idleSince = System.nanoTime()
            val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val t = bufferInfo.presentationTimeUs - info.originUs
            val render = bufferInfo.size > 0 && t >= skipBeforeUs
            decoder.releaseOutputBuffer(index, render)
            if (eos) outputDone = true
            if (render) {
                awaitFrame()
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(texMatrix)
                lastReturnedUs = t
                return t
            }
        }
        return null
    }

    private fun feedInput() {
        while (true) {
            val index = decoder.dequeueInputBuffer(0)
            if (index < 0) return
            val buf = decoder.getInputBuffer(index)!!
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) {
                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
                return
            }
            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun awaitFrame() {
        synchronized(lock) {
            val deadline = System.nanoTime() + FRAME_TIMEOUT_NS
            while (!frameAvailable) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                check(remaining > 0) { "Decoder frame timed out" }
                lock.wait(remaining)
            }
            frameAvailable = false
        }
    }

    override fun close() {
        runCatching { decoder.stop() }
        decoder.release()
        extractor.release()
        surface.release()
        surfaceTexture.release()
        callbackThread.quitSafely()
    }

    private companion object {
        const val SKIP_MARGIN_US = 250_000L
        const val FRAME_TIMEOUT_NS = 2_000_000_000L
        const val STALL_TIMEOUT_NS = 8_000_000_000L
        const val TAG = "VitsDecode"
    }
}

internal class DecoderStalledException(message: String) : IllegalStateException(message)
