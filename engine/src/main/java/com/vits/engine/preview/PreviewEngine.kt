package com.vits.engine.preview

import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES30.GL_COLOR_BUFFER_BIT
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glClear
import android.opengl.GLES30.glClearColor
import android.opengl.GLES30.glViewport
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import com.vits.engine.AssetResolver
import com.vits.engine.gl.EglCore
import com.vits.engine.interp.FlowQuality
import com.vits.engine.render.CompositionRenderer
import com.vits.project.Project
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real-time player for a [Project]. All GPU and codec work happens on one render thread,
 * paced by Choreographer; if a frame takes too long, playback skips ahead instead of drifting.
 * Public methods are thread-safe; [Listener] callbacks arrive on the main thread.
 */
class PreviewEngine(private val resolver: AssetResolver, private val listener: Listener) {
    interface Listener {
        fun onPosition(timelineUs: Long, durationUs: Long)
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
    private var renderer: CompositionRenderer? = null
    private var project: Project? = null
    private var positionUs = 0L
    private var playing = false
    private var clockStartNs = -1L
    private var clockStartUs = 0L
    private var pendingSeek: Long? = null

    init {
        handler.post {
            egl = EglCore()
            pbuffer = egl.createPbufferSurface(1, 1)
            egl.makeCurrent(pbuffer)
            choreographer = Choreographer.getInstance()
        }
    }

    /**
     * Shows [project] (null unloads it and frees every decoder). [positionUs] moves the playhead;
     * omit it to keep the current one, clamped to the new duration.
     */
    fun setProject(project: Project?, positionUs: Long? = null) = post {
        this.project = project
        if (project == null || project.main.clips.isEmpty()) {
            stopPlayback()
            renderer?.close()
            renderer = null
            return@post
        }
        val r = renderer ?: run {
            makeCurrent()
            CompositionRenderer(resolver, FlowQuality.PREVIEW).also { renderer = it }
        }
        r.project = project
        this.positionUs = (positionUs ?: this.positionUs).coerceIn(0, project.durationUs)
        if (playing) restartClock() else drawFrame()
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

    fun play() = post {
        val p = project ?: return@post
        if (playing || renderer == null) return@post
        if (positionUs >= p.durationUs - 1000) positionUs = 0
        playing = true
        restartClock()
        choreographer.postFrameCallback(frameCallback)
        notifyPlaying()
    }

    fun pause() = post { stopPlayback() }

    /** Scrubs to output time [outputUs]. Bursts of seeks collapse into the latest one. */
    fun seekTo(timelineUs: Long) {
        handler.post {
            val first = pendingSeek == null
            pendingSeek = timelineUs
            if (first) handler.post {
                val target = pendingSeek ?: return@post
                pendingSeek = null
                positionUs = target.coerceIn(0, project?.durationUs ?: 0)
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
            val p = project ?: return
            if (clockStartNs < 0) clockStartNs = frameTimeNanos
            positionUs = clockStartUs + (frameTimeNanos - clockStartNs) / 1000
            val end = positionUs >= p.durationUs
            if (end) positionUs = p.durationUs
            drawFrame()
            if (end) stopPlayback() else choreographer.postFrameCallback(this)
        }
    }

    private fun drawFrame() {
        val r = renderer ?: return
        val p = project ?: return
        if (window == EGL14.EGL_NO_SURFACE) return
        try {
            egl.makeCurrent(window)
            val canvas = p.resolvedCanvas
            r.render(positionUs) {
                glBindFramebuffer(GL_FRAMEBUFFER, 0)
                glViewport(0, 0, surfaceWidth, surfaceHeight)
                glClearColor(0f, 0f, 0f, 1f)
                glClear(GL_COLOR_BUFFER_BIT)
                letterbox(canvas.width.toFloat() / canvas.height)
            }
            egl.swapBuffers(window)
            val pos = positionUs
            val d = p.durationUs
            main.post { listener.onPosition(pos, d) }
        } catch (t: Throwable) {
            Log.e(TAG, "render failed", t)
            stopPlayback()
            main.post { listener.onError(t) }
        }
    }

    /** Restricts drawing to the largest rectangle of [aspect] centred in the surface. */
    private fun letterbox(aspect: Float) {
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight
        if (aspect > surfaceAspect) {
            val h = (surfaceWidth / aspect).toInt()
            glViewport(0, (surfaceHeight - h) / 2, surfaceWidth, h)
        } else {
            val w = (surfaceHeight * aspect).toInt()
            glViewport((surfaceWidth - w) / 2, 0, w, surfaceHeight)
        }
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
