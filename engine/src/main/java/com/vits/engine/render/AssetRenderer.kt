package com.vits.engine.render

import com.vits.engine.MediaInput
import com.vits.engine.gl.GlKit
import com.vits.engine.interp.FlowQuality
import com.vits.engine.interp.OpticalFlow
import com.vits.project.MediaAsset
import com.vits.project.Smoothing
import java.io.Closeable

/**
 * Turns "show source time t of this file" into pixels: decoding, the two-frame cache and, for
 * slow motion, frame synthesis. One per clip being played (each owns a decoder). GPU programs
 * come from the shared [gl]; create, use and close on its GL thread.
 */
internal class AssetRenderer(
    input: MediaInput,
    private val asset: MediaAsset,
    private val gl: GlKit,
    private val flowQuality: FlowQuality,
) : Closeable {
    private val cache = FrameCache(input, asset, gl)
    private var flow: OpticalFlow? = null

    /** Decodes ahead so a later [render] at [sourceUs] is instant (used to pre-roll the next clip). */
    fun prepare(sourceUs: Long) = cache.ensure(sourceUs + SNAP_US)

    /**
     * Draws source time [sourceUs] into whatever [bindTarget] binds. [speed] is the local playback
     * speed there; frames are synthesized only below 1x. [endUs] is the clip's out-point: frames
     * after it were cut away and are never blended in.
     */
    fun render(
        sourceUs: Long,
        speed: Double,
        smoothing: Smoothing,
        endUs: Long,
        posMatrix: FloatArray,
        bindTarget: () -> Unit,
    ) {
        // Timestamps carry µs rounding (33333 vs 33334); without snapping, a request landing a hair
        // before a real frame would show the previous one, a visible stutter.
        val snapped = sourceUs + SNAP_US
        cache.ensure(snapped)
        val (a, rawB) = cache.bracket(snapped)
        if (a == null) {
            bindTarget()
            return
        }
        val b = rawB?.takeIf { it.pts <= endUs + SNAP_US }
        val t = if (b == null) 0f else ((sourceUs - a.pts).toFloat() / (b.pts - a.pts)).coerceIn(0f, 1f)
        val slow = speed < 0.999

        if (b == null || !slow || smoothing == Smoothing.OFF || t < EDGE || t > 1 - EDGE) {
            // Real time or faster: nearest frame. Slow-mo without smoothing: hold the previous one.
            val holdPrevious = slow && smoothing == Smoothing.OFF
            drawFrame(if (b != null && !holdPrevious && t > 0.5f) b else a, posMatrix, bindTarget)
            return
        }

        val of = if (smoothing == Smoothing.OPTICAL_FLOW && gl.halfFloatTargets) {
            flow ?: OpticalFlow(gl, asset.width, asset.height, flowQuality).also { flow = it }
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
            p.vec2("uFrameSize", asset.width.toFloat(), asset.height.toFloat())
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
    }

    private companion object {
        /** Within this fraction of a real frame, just show it; saves work at near-1x speeds. */
        const val EDGE = 0.02f
        const val SNAP_US = 1_000L
    }
}
