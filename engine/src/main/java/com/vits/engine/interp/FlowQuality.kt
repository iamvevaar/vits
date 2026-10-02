package com.vits.engine.interp

/**
 * Cost/quality tiers for optical flow. Preview must hold the display rate; export can spend
 * many times longer per frame (the "longer time, better quality" trade-off).
 */
internal enum class FlowQuality(
    /** Long side of the finest flow level; frames larger than this are area-downsampled. */
    val maxSide: Int,
    val lkRadius: Int,
    val lkCoarseIterations: Int,
    val lkFineIterations: Int,
    val variationalIterations: Int,
    val holeFillPasses: Int,
) {
    PREVIEW(maxSide = 480, lkRadius = 2, lkCoarseIterations = 3, lkFineIterations = 2, variationalIterations = 3, holeFillPasses = 4),
    EXPORT(maxSide = 1280, lkRadius = 3, lkCoarseIterations = 5, lkFineIterations = 3, variationalIterations = 12, holeFillPasses = 5),
}
