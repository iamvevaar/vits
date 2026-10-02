package com.vits.engine.render

import android.opengl.Matrix
import com.vits.engine.AssetResolver
import com.vits.engine.gl.GlKit
import com.vits.engine.interp.FlowQuality
import com.vits.project.Canvas
import com.vits.project.MediaAsset
import com.vits.project.Project
import com.vits.project.placementAt
import java.io.Closeable

/**
 * Renders a [Project] at any timeline time: finds the clip, maps to its source time, and draws it
 * upright and letterboxed into the project canvas. Shared by preview and export.
 *
 * Each clip on screen (and the next one, pre-rolled before the cut) owns an [AssetRenderer] with
 * its own decoder, so cuts between clips, even two parts of the same file in reverse order, never
 * stall on a seek. GL thread only.
 */
internal class CompositionRenderer(
    private val resolver: AssetResolver,
    private val quality: FlowQuality,
) : Closeable {
    private val gl = GlKit()
    private val renderers = LinkedHashMap<String, AssetRenderer>(4, 0.75f, true)  // LRU by clip id
    private val posMatrix = FloatArray(16)

    var project: Project? = null
        set(value) {
            field = value
            // Close decoders of clips that are gone (edits replace clips with new ids or ranges).
            val live = value?.main?.clips?.mapTo(HashSet()) { rendererKey(it.id, it.assetId) }.orEmpty()
            renderers.keys.filter { it !in live }.forEach { renderers.remove(it)?.close() }
        }

    /**
     * Draws timeline time [timelineUs]. [bindTarget] must bind the output, clear it, and set the
     * viewport to the canvas rectangle; [outerMatrix] is applied last (e.g. to rotate for an
     * encoder that only accepts landscape sizes).
     */
    fun render(timelineUs: Long, outerMatrix: FloatArray? = null, bindTarget: () -> Unit) {
        val p = project
        val placement = p?.placementAt(timelineUs)
        if (p == null || placement == null) {
            bindTarget()
            return
        }
        val clip = placement.clip
        val asset = p.asset(clip.assetId)
        fitIntoCanvas(asset, p.resolvedCanvas, outerMatrix)
        rendererFor(clip.id, asset).render(
            placement.sourceUs, placement.speed, clip.smoothing, clip.sourceEndUs, posMatrix, bindTarget,
        )

        // Pre-roll the next clip shortly before the cut so the switch costs nothing.
        val next = p.main.clips.getOrNull(placement.index + 1)
        if (next != null && clip.durationUs - placement.localUs < PREROLL_US) {
            rendererFor(next.id, p.asset(next.assetId)).prepare(next.sourceStartUs)
        }
    }

    private fun rendererFor(clipId: String, asset: MediaAsset): AssetRenderer {
        val key = rendererKey(clipId, asset.id)
        renderers[key]?.let { return it }
        while (renderers.size >= MAX_DECODERS) {
            val eldest = renderers.keys.first()
            renderers.remove(eldest)?.close()
        }
        return AssetRenderer(resolver.open(asset), asset, gl, quality).also { renderers[key] = it }
    }

    /** Rotates coded frames upright and letterboxes them inside the canvas aspect. */
    private fun fitIntoCanvas(asset: MediaAsset, canvas: Canvas, outer: FloatArray?) {
        val videoAspect = asset.displayWidth.toFloat() / asset.displayHeight
        val canvasAspect = canvas.width.toFloat() / canvas.height
        val sx = if (videoAspect > canvasAspect) 1f else videoAspect / canvasAspect
        val sy = if (videoAspect > canvasAspect) canvasAspect / videoAspect else 1f
        Matrix.setIdentityM(posMatrix, 0)
        if (outer != null) Matrix.multiplyMM(posMatrix, 0, outer, 0, posMatrix.copyOf(), 0)
        Matrix.scaleM(posMatrix, 0, sx, sy, 1f)
        Matrix.rotateM(posMatrix, 0, -asset.rotation.toFloat(), 0f, 0f, 1f)
    }

    override fun close() {
        renderers.values.forEach { it.close() }
        renderers.clear()
        gl.release()
    }

    private companion object {
        /** Current clip + pre-rolled next clip. */
        const val MAX_DECODERS = 2
        const val PREROLL_US = 800_000L

        fun rendererKey(clipId: String, assetId: String) = "$clipId/$assetId"
    }
}
