package com.vits.engine.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30.*
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal val IDENTITY = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

internal class GlProgram(vertex: String, fragment: String) {
    val id: Int = glCreateProgram()
    private val locations = HashMap<String, Int>()

    init {
        val vs = compile(GL_VERTEX_SHADER, vertex)
        val fs = compile(GL_FRAGMENT_SHADER, fragment)
        glAttachShader(id, vs)
        glAttachShader(id, fs)
        // GLSL ES 1.00 shaders can't use layout qualifiers; pin attribute slots for both dialects.
        glBindAttribLocation(id, 0, "aPos")
        glBindAttribLocation(id, 1, "aUv")
        glLinkProgram(id)
        val ok = IntArray(1)
        glGetProgramiv(id, GL_LINK_STATUS, ok, 0)
        check(ok[0] == GL_TRUE) { "link failed: ${glGetProgramInfoLog(id)}" }
        glDeleteShader(vs)
        glDeleteShader(fs)
    }

    fun use() = glUseProgram(id)

    private fun loc(name: String) = locations.getOrPut(name) { glGetUniformLocation(id, name) }

    fun int(name: String, v: Int) = glUniform1i(loc(name), v)
    fun float(name: String, v: Float) = glUniform1f(loc(name), v)
    fun vec2(name: String, x: Float, y: Float) = glUniform2f(loc(name), x, y)
    fun vec3(name: String, x: Float, y: Float, z: Float) = glUniform3f(loc(name), x, y, z)
    fun mat4(name: String, m: FloatArray) = glUniformMatrix4fv(loc(name), 1, false, m, 0)

    fun texture(name: String, unit: Int, tex: Int, target: Int = GL_TEXTURE_2D) {
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindTexture(target, tex)
        int(name, unit)
    }

    fun release() = glDeleteProgram(id)

    private fun compile(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GL_TRUE) { "shader compile failed: ${glGetShaderInfoLog(s)}\n$src" }
        return s
    }
}

/** A 2D texture with an attached framebuffer so it can be rendered into. */
internal class RenderTexture(
    val width: Int,
    val height: Int,
    val format: Format,
    withDepth: Boolean = false,
) {
    enum class Format(val internal: Int, val pixel: Int, val type: Int) {
        RGBA8(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE),
        RGBA16F(GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT),
    }

    val texture: Int
    private val fbo: Int
    private val depthBuffer: Int

    init {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        texture = ids[0]
        glBindTexture(GL_TEXTURE_2D, texture)
        glTexImage2D(GL_TEXTURE_2D, 0, format.internal, width, height, 0, format.pixel, format.type, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
        depthBuffer = if (withDepth) {
            glGenRenderbuffers(1, ids, 0)
            glBindRenderbuffer(GL_RENDERBUFFER, ids[0])
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, width, height)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, ids[0])
            ids[0]
        } else {
            0
        }
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        check(status == GL_FRAMEBUFFER_COMPLETE) { "framebuffer incomplete ($format): 0x${status.toString(16)}" }
    }

    fun bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glViewport(0, 0, width, height)
    }

    fun release() {
        if (depthBuffer != 0) glDeleteRenderbuffers(1, intArrayOf(depthBuffer), 0)
        glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

/** Full-screen quad: aPos in clip space, aUv in 0..1 with GL's bottom-left origin. */
internal class Quad {
    private val vao: Int
    private val vbo: Int

    init {
        val data = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data).position(0)
        val ids = IntArray(1)
        glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        glGenBuffers(1, ids, 0)
        vbo = ids[0]
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, buf, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8)
        glBindVertexArray(0)
    }

    fun draw() {
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glBindVertexArray(0)
    }

    /** Draws [count] attribute-less points; the vertex shader derives everything from gl_VertexID. */
    fun drawPoints(count: Int) {
        glBindVertexArray(vao)
        glDrawArrays(GL_POINTS, 0, count)
        glBindVertexArray(0)
    }

    fun release() {
        glDeleteBuffers(1, intArrayOf(vbo), 0)
        glDeleteVertexArrays(1, intArrayOf(vao), 0)
    }
}

internal fun createOesTexture(): Int {
    val ids = IntArray(1)
    glGenTextures(1, ids, 0)
    glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
    glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
    glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    return ids[0]
}

/** Half-float render targets are needed for optical flow; core in ES 3.2, an extension before. */
internal fun supportsHalfFloatTargets(): Boolean {
    val ext = glGetString(GL_EXTENSIONS) ?: return false
    val version = glGetString(GL_VERSION) ?: ""
    return "GL_EXT_color_buffer_half_float" in ext || "GL_EXT_color_buffer_float" in ext ||
        version.contains("OpenGL ES 3.2")
}
