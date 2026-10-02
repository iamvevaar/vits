package com.vits.engine.interp

import android.opengl.GLES30.GL_COLOR_BUFFER_BIT
import android.opengl.GLES30.GL_DEPTH_BUFFER_BIT
import android.opengl.GLES30.GL_DEPTH_TEST
import android.opengl.GLES30.GL_LESS
import android.opengl.GLES30.GL_RGBA
import android.opengl.GLES30.GL_UNSIGNED_BYTE
import android.opengl.GLES30.glClear
import android.opengl.GLES30.glClearColor
import android.opengl.GLES30.glClearDepthf
import android.opengl.GLES30.glDepthFunc
import android.opengl.GLES30.glDepthMask
import android.opengl.GLES30.glDisable
import android.opengl.GLES30.glEnable
import android.opengl.GLES30.glReadPixels
import android.opengl.GLES30.glUniform2i
import android.opengl.GLES30.glGetUniformLocation
import com.vits.engine.gl.GlKit
import com.vits.engine.gl.RenderTexture
import com.vits.engine.gl.RenderTexture.Format.RGBA16F
import com.vits.engine.gl.RenderTexture.Format.RGBA8
import com.vits.engine.render.FrameSlot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Motion-compensated frame synthesis, entirely in fragment/vertex shaders.
 *
 * Estimation (per frame pair, both directions), coarse to fine over an anti-aliased pyramid:
 *   upsample → Lucas–Kanade with brightness offset → 3×3 median → variational refinement.
 * Synthesis (per output frame): two-sided z-buffered forward splatting of the flow to time t,
 * hole filling, then an occlusion-aware composite. Flow results are cached per pair, so slow
 * motion pays for one estimation per source frame, however many frames are synthesized from it.
 */
internal class OpticalFlow(
    private val gl: GlKit,
    frameWidth: Int,
    frameHeight: Int,
    private val quality: FlowQuality,
) {
    private val sizes: List<Pair<Int, Int>>
    private val luma: List<RenderTexture>
    private val ping: List<RenderTexture>
    private val pong: List<RenderTexture>
    private val base: List<RenderTexture>
    private val warp: List<RenderTexture>
    private val pyramids = HashMap<FrameSlot, Pyramid>()

    val flow01: RenderTexture
    val flow10: RenderTexture
    private val flowT: RenderTexture
    private val flowTScratch: RenderTexture
    private val cutTarget = RenderTexture(1, 1, RGBA8)
    private val pixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

    private var cachedPair = -1L to -1L
    private var cachedUsable = false
    private var pyrA: Pyramid? = null
    private var pyrB: Pyramid? = null

    /** The flow at time t (0→1 direction, level-0 pixels) after [synthesize]. */
    lateinit var flowAtT: RenderTexture
        private set

    private class Pyramid(val levels: List<RenderTexture>) {
        var generation = -1L
    }

    init {
        val scale = min(1f, quality.maxSide.toFloat() / max(frameWidth, frameHeight))
        var w = (frameWidth * scale).roundToInt()
        var h = (frameHeight * scale).roundToInt()
        val s = ArrayList<Pair<Int, Int>>()
        while (s.size < MAX_LEVELS && min(w, h) >= MIN_LEVEL_SIDE) {
            s += w to h
            w = (w + 1) / 2
            h = (h + 1) / 2
        }
        if (s.isEmpty()) s += max(w, 1) to max(h, 1)
        sizes = s
        fun level() = sizes.map { (w, h) -> RenderTexture(w, h, RGBA16F) }
        luma = level()
        ping = level()
        pong = level()
        base = level()
        warp = level()
        val (w0, h0) = sizes[0]
        flow01 = RenderTexture(w0, h0, RGBA16F)
        flow10 = RenderTexture(w0, h0, RGBA16F)
        flowT = RenderTexture(w0, h0, RGBA16F, withDepth = true)
        flowTScratch = RenderTexture(w0, h0, RGBA16F)
    }

    /** Converts level-0 flow pixels to texture-coordinate offsets. */
    val flowToUvX get() = 1f / sizes[0].first
    val flowToUvY get() = 1f / sizes[0].second

    /**
     * Estimates motion between [a] and [b] (cached per pair). Returns false when the pair cannot
     * be interpolated meaningfully (a scene cut), in which case callers should not synthesize.
     */
    fun compute(a: FrameSlot, b: FrameSlot): Boolean {
        val key = a.generation to b.generation
        if (key == cachedPair) return cachedUsable
        val pa = pyramidFor(a)
        val pb = pyramidFor(b)
        solve(pa, pb, flow01)
        solve(pb, pa, flow10)
        pyrA = pa
        pyrB = pb
        cachedUsable = failureRatio(pa, pb) < CUT_THRESHOLD
        cachedPair = key
        return cachedUsable
    }

    /** Fills [flowAtT] with the motion field at time [t] between the last computed pair. */
    fun synthesize(t: Float) {
        val pa = pyrA!!
        val pb = pyrB!!
        val (w, h) = sizes[0]
        flowT.bind()
        glClearColor(0f, 0f, 0f, 0f)
        glClearDepthf(1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LESS)
        glDepthMask(true)
        val p = gl.splat
        p.use()
        glUniform2i(glGetUniformLocation(p.id, "uSize"), w, h)
        // Frame 0 travels forward by t along F01; frame 1 travels back by (1−t) along F10.
        splatFrom(flow01, flow10, pa, pb, t, 1f, w * h)
        splatFrom(flow10, flow01, pb, pa, 1f - t, -1f, w * h)
        glDisable(GL_DEPTH_TEST)

        var src = flowT
        var dst = flowTScratch
        for (i in 0 until quality.holeFillPasses) {
            dst.bind()
            gl.holeFill.use()
            gl.holeFill.texture("uFlow", 0, src.texture)
            gl.holeFill.int("uStep", 1 shl i)
            gl.draw(gl.holeFill)
            val tmp = src; src = dst; dst = tmp
        }
        flowAtT = src
    }

    private fun splatFrom(flowA: RenderTexture, flowB: RenderTexture, a: Pyramid, b: Pyramid, t: Float, sign: Float, count: Int) {
        val p = gl.splat
        p.texture("uFlowA", 0, flowA.texture)
        p.texture("uFlowB", 1, flowB.texture)
        p.texture("uPyrA", 2, a.levels[0].texture)
        p.texture("uPyrB", 3, b.levels[0].texture)
        p.float("uT", t)
        p.float("uSign", sign)
        gl.drawPoints(count)
    }

    private fun pyramidFor(slot: FrameSlot): Pyramid {
        val pyr = pyramids.getOrPut(slot) { Pyramid(sizes.map { (w, h) -> RenderTexture(w, h, RGBA16F) }) }
        if (pyr.generation == slot.generation) return pyr
        for (l in sizes.indices) {
            luma[l].bind()
            if (l == 0) {
                gl.lumaArea.use()
                gl.lumaArea.texture("uTex", 0, slot.texture.texture)
                gl.lumaArea.vec2("uDstTexel", 1f / sizes[0].first, 1f / sizes[0].second)
                gl.draw(gl.lumaArea)
            } else {
                gl.gaussDown.use()
                gl.gaussDown.texture("uTex", 0, luma[l - 1].texture)
                gl.draw(gl.gaussDown)
            }
            pyr.levels[l].bind()
            gl.gradient.use()
            gl.gradient.texture("uTex", 0, luma[l].texture)
            gl.draw(gl.gradient)
        }
        pyr.generation = slot.generation
        return pyr
    }

    private fun solve(p0: Pyramid, p1: Pyramid, out: RenderTexture) {
        var coarser: RenderTexture? = null
        val lk = gl.lucasKanade(quality.lkRadius)
        for (l in sizes.indices.reversed()) {
            val (w, h) = sizes[l]
            var cur = ping[l]
            var other = pong[l]

            cur.bind()
            gl.flowUpsample.use()
            gl.flowUpsample.texture("uFlow", 0, (coarser ?: p0.levels[l]).texture)
            val c = coarser
            if (c != null) {
                gl.flowUpsample.vec2("uScale", w.toFloat() / c.width, h.toFloat() / c.height)
                gl.flowUpsample.float("uEnabled", 1f)
            } else {
                gl.flowUpsample.vec2("uScale", 0f, 0f)
                gl.flowUpsample.float("uEnabled", 0f)
            }
            gl.draw(gl.flowUpsample)

            repeat(if (l == 0) quality.lkFineIterations else quality.lkCoarseIterations) {
                other.bind()
                lk.use()
                lk.texture("uPyr0", 0, p0.levels[l].texture)
                lk.texture("uPyr1", 1, p1.levels[l].texture)
                lk.texture("uFlow", 2, cur.texture)
                lk.vec2("uInvSize", 1f / w, 1f / h)
                gl.draw(lk)
                val t = cur; cur = other; other = t
            }

            base[l].bind()
            gl.median.use()
            gl.median.texture("uFlow", 0, cur.texture)
            gl.draw(gl.median)
            var result = base[l]

            if (quality.variationalIterations > 0) {
                warp[l].bind()
                gl.warpPrep.use()
                gl.warpPrep.texture("uPyr0", 0, p0.levels[l].texture)
                gl.warpPrep.texture("uPyr1", 1, p1.levels[l].texture)
                gl.warpPrep.texture("uFlow0", 2, base[l].texture)
                gl.warpPrep.vec2("uInvSize", 1f / w, 1f / h)
                gl.draw(gl.warpPrep)

                var src = base[l]
                var dst = ping[l]
                repeat(quality.variationalIterations) {
                    dst.bind()
                    val v = gl.variational
                    v.use()
                    v.texture("uFlow", 0, src.texture)
                    v.texture("uFlow0", 1, base[l].texture)
                    v.texture("uWarp", 2, warp[l].texture)
                    v.texture("uPyr0", 3, p0.levels[l].texture)
                    v.float("uAlpha", VARIATIONAL_ALPHA)
                    gl.draw(v)
                    src = dst
                    dst = if (dst === ping[l]) pong[l] else ping[l]
                }
                result = src
            }

            if (l == 0) {
                out.bind()
                gl.copy.use()
                gl.copy.texture("uTex", 0, result.texture)
                gl.draw(gl.copy)
            }
            coarser = result
        }
    }

    /** Share of sampled pixels whose correspondence fails; one 4-byte readback per pair. */
    private fun failureRatio(p0: Pyramid, p1: Pyramid): Float {
        cutTarget.bind()
        val p = gl.cutDetect
        p.use()
        p.texture("uFlow01", 0, flow01.texture)
        p.texture("uFlow10", 1, flow10.texture)
        p.texture("uPyr0", 2, p0.levels[0].texture)
        p.texture("uPyr1", 3, p1.levels[0].texture)
        gl.draw(p)
        pixel.clear()
        glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel)
        return (pixel.get(0).toInt() and 0xFF) / 255f
    }

    fun release() {
        (luma + ping + pong + base + warp + listOf(flow01, flow10, flowT, flowTScratch, cutTarget))
            .forEach { it.release() }
        pyramids.values.forEach { p -> p.levels.forEach { it.release() } }
        pyramids.clear()
    }

    private companion object {
        const val MAX_LEVELS = 6
        const val MIN_LEVEL_SIDE = 16
        const val VARIATIONAL_ALPHA = 0.08f
        /** Above this share of failed correspondences the pair is treated as a scene cut. */
        const val CUT_THRESHOLD = 0.6f
    }
}
