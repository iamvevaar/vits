package com.vits.engine.preview

import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES30.GL_COLOR_BUFFER_BIT
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glClear
import android.opengl.GLES30.glClearColor
import android.opengl.GLES30.glViewport
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import com.vits.engine.MediaInfo
import com.vits.engine.MediaInput
import com.vits.engine.SmoothMode
import com.vits.engine.gl.EglCore
import com.vits.engine.interp.FlowQuality
import com.vits.engine.render.TimelineRenderer
import com.vits.timeline.SpeedSpec
import com.vits.timeline.TimeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real-time player for a retimed clip. All GPU and codec work happens on one render thread,
 * paced by Choreographer; if a frame takes too long, playback skips ahead instead of drifting.
 * Public methods are thread-safe; [Listener] callbacks arrive on the main thread.
 */
class PreviewEngine(private val listener: Listener) {
    interface Listener {
        fun onPosition(outputUs: Long, durationUs: Long)
        fun onPlayingChanged(playing: Boolean)
        fun onError(error: Throwable)
    }

    private val thread = HandlerThread("vits-render").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    // Render-thread state below.
    private lateinit var egl: EglCore
    private lateinit var choreographer: Choreographer
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var info: MediaInfo? = null
    private var renderer: TimelineRenderer? = null
    private var spec: SpeedSpec = SpeedSpec.Constant(1.0)
    private var map: TimeMap? = null
    private var mode = SmoothMode.FRAME_BLENDING
    private var positionUs = 0L
    private var playing = false
    private var clockStartNs = -1L
    private var clockStartUs = 0L
    private var pendingSeek: Long? = null
    private val posMatrix = FloatArray(16)

    init {
        handler.post {
            egl = EglCore()
            pbuffer = egl.createPbufferSurface(1, 1)
            egl.makeCurrent(pbuffer)
            choreographer = Choreographer.getInstance()
        }
    }

    /** Loads a clip (or unloads with null), keeping the current speed settings. */
    fun open(input: MediaInput?, info: MediaInfo?, startOutputUs: Long = 0) = post {
        Log.d(TAG, "open $info")
        stopPlayback()
        renderer?.close()
        renderer = null
        this.info = info
        if (input != null && info != null) {
            makeCurrent()
            renderer = TimelineRenderer(input, info, FlowQuality.PREVIEW)
            map = TimeMap(spec, info.durationUs)
            positionUs = startOutputUs.coerceIn(0, map!!.outputDurationUs)
            drawFrame()
        } else {
            map = null
        }
    }

    /**
     * Attaches the display surface. Detaching (null) blocks until the render thread lets go,
     * because the caller's Surface becomes invalid as soon as surfaceDestroyed returns.
     */
    fun setSurface(surface: Surface?, width: Int = 0, height: Int = 0) {
        val done = CountDownLatch(1)
        post {
            try {
                attachSurface(surface, width, height)
            } finally {
                done.countDown()
            }
        }
        if (surface == null) done.await(2, TimeUnit.SECONDS)
    }

    private fun attachSurface(surface: Surface?, width: Int, height: Int) {
        Log.d(TAG, "surface $surface ${width}x$height")
        if (window != EGL14.EGL_NO_SURFACE) {
            egl.makeCurrent(pbuffer)
            egl.releaseSurface(window)
            window = EGL14.EGL_NO_SURFACE
        }
        if (surface != null) {
            window = egl.createWindowSurface(surface)
            surfaceWidth = width
            surfaceHeight = height
            drawFrame()
        }
    }

    /** Changes the speed; the source frame on screen stays put while the output timeline re-flows. */
    fun setSpeed(spec: SpeedSpec) = post {
        this.spec = spec
        val info = info ?: return@post
        val old = map
        val source = old?.sourceTimeAt(positionUs) ?: 0L
        map = TimeMap(spec, info.durationUs)
        positionUs = map!!.outputTimeAt(source)
        if (playing) restartClock() else drawFrame()
    }

    fun setSmoothMode(mode: SmoothMode) = post {
        this.mode = mode
        if (!playing) drawFrame()
    }

    fun play() = post {
        val m = map ?: return@post
        if (playing) return@post
        if (positionUs >= m.outputDurationUs - 1000) positionUs = 0
        playing = true
        restartClock()
        choreographer.postFrameCallback(frameCallback)
        notifyPlaying()
    }

    fun pause() = post { stopPlayback() }

    /** Scrubs to output time [outputUs]. Bursts of seeks collapse into the latest one. */
    fun seekTo(outputUs: Long) {
        handler.post {
            val first = pendingSeek == null
            pendingSeek = outputUs
            if (first) handler.post {
                val target = pendingSeek ?: return@post
                pendingSeek = null
                positionUs = target.coerceIn(0, map?.outputDurationUs ?: 0)
                if (playing) restartClock() else drawFrame()
            }
        }
    }

    fun release() {
        handler.post {
            stopPlayback()
            renderer?.close()
            renderer = null
            if (window != EGL14.EGL_NO_SURFACE) egl.releaseSurface(window)
            egl.releaseSurface(pbuffer)
            egl.release()
            thread.quitSafely()
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!playing) return
            val m = map ?: return
            if (clockStartNs < 0) clockStartNs = frameTimeNanos
            positionUs = clockStartUs + (frameTimeNanos - clockStartNs) / 1000
            val end = positionUs >= m.outputDurationUs
            if (end) positionUs = m.outputDurationUs
            drawFrame()
            if (end) stopPlayback() else choreographer.postFrameCallback(this)
        }
    }

    private fun drawFrame() {
        val r = renderer ?: return
        val m = map ?: return
        val info = info ?: return
        if (window == EGL14.EGL_NO_SURFACE) return
        try {
            egl.makeCurrent(window)
            val source = m.sourceTimeAt(positionUs)
            computePosMatrix(info)
            r.render(source, m.speedAtSource(source), mode, posMatrix) {
                glBindFramebuffer(GL_FRAMEBUFFER, 0)
                glViewport(0, 0, surfaceWidth, surfaceHeight)
                glClearColor(0f, 0f, 0f, 1f)
                glClear(GL_COLOR_BUFFER_BIT)
            }
            egl.swapBuffers(window)
            if (!playing) Log.d(TAG, "drew source=$source out=$positionUs")
            val p = positionUs
            val d = m.outputDurationUs
            main.post { listener.onPosition(p, d) }
        } catch (t: Throwable) {
            Log.e(TAG, "render failed", t)
            stopPlayback()
            main.post { listener.onError(t) }
        }
    }

    /** Rotates coded frames upright and letterboxes them into the surface. */
    private fun computePosMatrix(info: MediaInfo) {
        val videoAspect = info.displayWidth.toFloat() / info.displayHeight
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight
        val sx = if (videoAspect > surfaceAspect) 1f else videoAspect / surfaceAspect
        val sy = if (videoAspect > surfaceAspect) surfaceAspect / videoAspect else 1f
        Matrix.setIdentityM(posMatrix, 0)
        Matrix.scaleM(posMatrix, 0, sx, sy, 1f)
        Matrix.rotateM(posMatrix, 0, -info.rotation.toFloat(), 0f, 0f, 1f)
    }

    private fun restartClock() {
        clockStartNs = -1
        clockStartUs = positionUs
    }

    private fun stopPlayback() {
        if (!playing) return
        playing = false
        choreographer.removeFrameCallback(frameCallback)
        notifyPlaying()
    }

    private fun notifyPlaying() {
        val p = playing
        main.post { listener.onPlayingChanged(p) }
    }

    private fun makeCurrent() {
        egl.makeCurrent(if (window != EGL14.EGL_NO_SURFACE) window else pbuffer)
    }

    private fun post(block: () -> Unit) {
        handler.post {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "command failed", t)
                main.post { listener.onError(t) }
            }
        }
    }

    private companion object {
        const val TAG = "VitsPreview"
    }
}
