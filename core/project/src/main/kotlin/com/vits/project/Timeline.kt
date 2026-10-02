package com.vits.project

/** Where timeline time [timelineUs] falls: inside [clip] (the [index]th), [localUs] after its start. */
data class Placement(val index: Int, val clip: Clip, val clipStartUs: Long, val localUs: Long) {
    /** Absolute source time shown at this instant. */
    val sourceUs: Long get() = clip.sourceStartUs + clip.timeMap.sourceTimeAt(localUs)

    /** Local playback speed at this instant. */
    val speed: Double get() = clip.timeMap.speedAtSource(sourceUs - clip.sourceStartUs)
}

/** The clip playing at [timelineUs]; the end of the timeline belongs to the last clip. */
fun Project.placementAt(timelineUs: Long): Placement? {
    val clips = main.clips
    if (clips.isEmpty()) return null
    var start = 0L
    for ((i, c) in clips.withIndex()) {
        val d = c.durationUs
        if (timelineUs < start + d || i == clips.lastIndex) {
            return Placement(i, c, start, (timelineUs - start).coerceIn(0, d))
        }
        start += d
    }
    error("unreachable")
}

/** Timeline start of the clip with [clipId]. */
fun Project.startOf(clipId: String): Long {
    var start = 0L
    for (c in main.clips) {
        if (c.id == clipId) return start
        start += c.durationUs
    }
    throw NoSuchElementException(clipId)
}

/** Timeline time at which [clipId] shows source time [sourceUs]. */
fun Project.timelineTimeOf(clipId: String, sourceUs: Long): Long {
    val clip = clip(clipId)
    val local = (sourceUs - clip.sourceStartUs).coerceIn(0, clip.sourceDurationUs)
    return startOf(clipId) + clip.timeMap.outputTimeAt(local)
}

fun Project.clip(clipId: String): Clip = main.clips.first { it.id == clipId }
