package com.vits.engine.render

import android.opengl.GLES11Ext
import android.opengl.GLES30.glDeleteTextures
import com.vits.engine.MediaInput
import com.vits.project.MediaAsset
import com.vits.engine.decode.VideoFrameSource
import com.vits.engine.gl.GlKit
import com.vits.engine.gl.RenderTexture
import com.vits.engine.gl.createOesTexture
import java.io.Closeable

internal class FrameSlot(val texture: RenderTexture) {
    var pts = -1L
    /** Bumped whenever the slot is overwritten, so derived data (pyramids, flow) can be keyed on it. */
    var generation = 0L
}

/**
 * Keeps the two source frames that bracket the current time resident on the GPU. Moving forward
 * decodes sequentially (cheap); jumping backward or far ahead seeks to a keyframe.
 */
internal class FrameCache(input: MediaInput, info: MediaAsset, private val gl: GlKit) : Closeable {
    private val oes = createOesTexture()
    private val source = VideoFrameSource(input, info, oes)
    private val slots = Array(3) { FrameSlot(RenderTexture(info.width, info.height, RenderTexture.Format.RGBA8)) }
    private var prev: FrameSlot? = null
    private var next: FrameSlot? = null
    private var eos = false
    private var positioned = false
    private var generations = 0L

    /** Decodes until the frames around [t] are resident. */
    fun ensure(t: Long) {
        val p = prev
        val n = next
        if (!positioned || (p != null && t < p.pts) || (n != null && !eos && t > n.pts + FORWARD_SEEK_US)) {
            seek(t)
        }
        while (!eos && (next == null || next!!.pts <= t)) advance()
    }

    /** Frames (a, b) with a.pts ≤ t < b.pts; b is null at the clip edges. */
    fun bracket(t: Long): Pair<FrameSlot?, FrameSlot?> {
        val p = prev
        val n = next ?: return p to null
        return when {
            n.pts <= t -> n to null
            p == null -> n to null
            else -> p to n
        }
    }

    private fun seek(t: Long) {
        source.seekTo(t)
        prev = null
        next = null
        eos = false
        positioned = true
    }

    private fun advance() {
        val slot = slots.first { it !== prev && it !== next }
        val pts = source.next()
        if (pts == null) {
            eos = true
            return
        }
        slot.texture.bind()
        gl.oesCopy.use()
        gl.oesCopy.texture("uTex", 0, oes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        gl.draw(gl.oesCopy, texMatrix = source.texMatrix)
        slot.pts = pts
        slot.generation = ++generations
        prev = next
        next = slot
    }

    override fun close() {
        source.close()
        slots.forEach { it.texture.release() }
        glDeleteTextures(1, intArrayOf(oes), 0)
    }

    private companion object {
        const val FORWARD_SEEK_US = 1_500_000L
    }
}
