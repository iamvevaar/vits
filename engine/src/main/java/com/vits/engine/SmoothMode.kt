package com.vits.engine

/** How frames are synthesized when the clip plays slower than it was recorded. */
enum class SmoothMode {
    /** Repeat the previous source frame (classic, steppy slow-mo). */
    OFF,

    /** Cross-fade the two neighbouring frames: never tears, slight ghosting on fast motion. */
    FRAME_BLENDING,

    /** Warp both neighbours along estimated motion: sharpest, may wobble at occlusions. */
    OPTICAL_FLOW,
}
