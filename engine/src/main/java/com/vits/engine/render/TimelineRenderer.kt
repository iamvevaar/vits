package com.vits.engine.render

import android.opengl.GLES30.GL_COLOR_BUFFER_BIT
import android.opengl.GLES30.glClear
import android.opengl.GLES30.glClearColor
import com.vits.engine.MediaInfo
import com.vits.engine.MediaInput
import com.vits.engine.SmoothMode
import com.vits.engine.gl.GlKit
import com.vits.engine.interp.FlowQuality
import com.vits.engine.interp.OpticalFlow
import java.io.Closeable

/**
 * Turns "show source time t" into pixels. Shared by preview and export so both produce
 * identical frames. Owns GPU state; create, use and close on one GL thread.
 */
internal class TimelineRenderer(
    input: MediaInput,
    info: MediaInfo,
    private val flowQuality: FlowQuality,
) : Closeable {
    private val gl = GlKit()
    private val cache = FrameCache(input, info, gl)
    private val frameWidth = info.width
    private val frameHeight = info.height
    private var flow: OpticalFlow? = null

    val opticalFlowSupported get() = gl.halfFloatTargets

    /**
     * Draws source time [sourceUs] into whatever [bindTarget] binds. [speed] is the local playback
     * speed there; frames are synthesized only when it is below 1x.
     */
    fun render(
        sourceUs: Long,
        speed: Double,
        mode: SmoothMode,
        posMatrix: FloatArray,
        bindTarget: () -> Unit,
    ) {
        // Timestamps carry µs rounding (33333 vs 33334); without snapping, a request landing a hair
        // before a real frame would show the previous one, a visible stutter.
        val snapped = sourceUs + SNAP_US
        cache.ensure(snapped)
        val (a, b) = cache.bracket(snapped)
        if (a == null) {
            bindTarget()
            glClearColor(0f, 0f, 0f, 1f)
            glClear(GL_COLOR_BUFFER_BIT)
            return
        }
        val t = if (b == null) 0f else ((sourceUs - a.pts).toFloat() / (b.pts - a.pts)).coerceIn(0f, 1f)
        val slow = speed < 0.999

        if (b == null || !slow || mode == SmoothMode.OFF || t < EDGE || t > 1 - EDGE) {
            // Real time or faster: nearest frame. Slow-mo without smoothing: hold the previous one.
            val holdPrevious = slow && mode == SmoothMode.OFF
            drawFrame(if (b != null && !holdPrevious && t > 0.5f) b else a, posMatrix, bindTarget)
            return
        }

        val of = if (mode == SmoothMode.OPTICAL_FLOW && gl.halfFloatTargets) {
            flow ?: OpticalFlow(gl, frameWidth, frameHeight, flowQuality).also { flow = it }
        } else {
            null
        }
        if (of != null) {
            if (!of.compute(a, b)) {
                // Scene cut: motion between these frames is meaningless, so cut cleanly at t = ½.
                drawFrame(if (t < 0.5f) a else b, posMatrix, bindTarget)
                return
            }
            of.synthesize(t)
            bindTarget()
            val p = gl.composite
            p.use()
            p.texture("uFrame0", 0, a.texture.texture)
            p.texture("uFrame1", 1, b.texture.texture)
            p.texture("uFlowT", 2, of.flowAtT.texture)
            p.texture("uFlow01", 3, of.flow01.texture)
            p.texture("uFlow10", 4, of.flow10.texture)
            p.float("uT", t)
            p.vec2("uFlowToUv", of.flowToUvX, of.flowToUvY)
            p.vec2("uFrameSize", frameWidth.toFloat(), frameHeight.toFloat())
            gl.draw(p, posMatrix)
        } else {
            bindTarget()
            val p = gl.blend
            p.use()
            p.texture("uFrame0", 0, a.texture.texture)
            p.texture("uFrame1", 1, b.texture.texture)
            p.float("uT", t)
            gl.draw(p, posMatrix)
        }
    }

    private fun drawFrame(frame: FrameSlot, posMatrix: FloatArray, bindTarget: () -> Unit) {
        bindTarget()
        gl.copy.use()
        gl.copy.texture("uTex", 0, frame.texture.texture)
        gl.draw(gl.copy, posMatrix)
    }

    override fun close() {
        cache.close()
        flow?.release()
        gl.release()
    }

    private companion object {
        /** Within this fraction of a real frame, just show it; saves work at near-1x speeds. */
        const val EDGE = 0.02f
        const val SNAP_US = 1_000L
    }
}
