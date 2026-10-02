package com.vits.engine.gl

/** Per-context GPU resources shared by every render stage. Create and use on the GL thread. */
internal class GlKit {
    private val quad = Quad()
    val halfFloatTargets = supportsHalfFloatTargets()
    private val programs = ArrayList<GlProgram>()

    private fun program(vs: String, fs: String) = GlProgram(vs, fs).also { programs += it }
    private fun lazyProgram(fs: String) = lazy { program(Shaders.VERTEX, fs) }

    val oesCopy = program(Shaders.OES_VERTEX, Shaders.OES_COPY)
    val copy = program(Shaders.VERTEX, Shaders.COPY)
    val blend = program(Shaders.VERTEX, Shaders.BLEND)
    val lumaArea by lazyProgram(Shaders.LUMA_AREA)
    val gaussDown by lazyProgram(Shaders.GAUSS_DOWN)
    val gradient by lazyProgram(Shaders.GRADIENT)
    val flowUpsample by lazyProgram(Shaders.FLOW_UPSAMPLE)
    val median by lazyProgram(Shaders.MEDIAN3)
    val warpPrep by lazyProgram(Shaders.WARP_PREP)
    val variational by lazyProgram(Shaders.VARIATIONAL)
    val holeFill by lazyProgram(Shaders.HOLE_FILL)
    val composite by lazyProgram(Shaders.COMPOSITE)
    val cutDetect by lazyProgram(Shaders.CUT_DETECT)
    val splat by lazy { program(Shaders.SPLAT_VERTEX, Shaders.SPLAT_FRAGMENT) }

    private val lkPrograms = HashMap<Int, GlProgram>()

    /** Lucas–Kanade program for a (2r+1)² window. */
    fun lucasKanade(radius: Int): GlProgram = lkPrograms.getOrPut(radius) {
        program(Shaders.VERTEX, Shaders.lucasKanade(radius, sigma = radius * 0.6f + 0.4f))
    }

    /** Draws the quad with [program] (already in use, samplers bound). */
    fun draw(program: GlProgram, posMatrix: FloatArray = IDENTITY, texMatrix: FloatArray = IDENTITY) {
        program.mat4("uPosMatrix", posMatrix)
        program.mat4("uTexMatrix", texMatrix)
        quad.draw()
    }

    fun drawPoints(count: Int) = quad.drawPoints(count)

    fun release() {
        quad.release()
        programs.forEach { it.release() }
    }
}
