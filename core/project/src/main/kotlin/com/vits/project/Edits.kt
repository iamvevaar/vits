package com.vits.project

import java.util.UUID

/** Source of fresh ids; injectable so tests are deterministic. */
fun interface IdSource {
    fun next(): String

    companion object {
        val Random = IdSource { UUID.randomUUID().toString() }
    }
}

/*
 * Every edit is a pure function Project → Project. Invalid requests throw
 * IllegalArgumentException; requests that would change nothing return the project unchanged
 * (same instance), which History uses to skip no-op undo steps.
 */

fun Project.withAsset(asset: MediaAsset): Project =
    if (assets.any { it.id == asset.id }) this else copy(assets = assets + asset)

/** Appends [asset] as a new clip spanning the whole file. */
fun Project.appendClip(asset: MediaAsset, ids: IdSource = IdSource.Random): Project =
    withAsset(asset).insertClip(main.clips.size, Clip(ids.next(), asset.id, 0, asset.durationUs))

fun Project.insertClip(index: Int, clip: Clip): Project {
    require(assets.any { it.id == clip.assetId }) { "unknown asset ${clip.assetId}" }
    require(clip.sourceEndUs <= asset(clip.assetId).durationUs) { "clip past end of media" }
    require(index in 0..main.clips.size)
    return withClips(main.clips.toMutableList().apply { add(index, clip) })
}

/**
 * Splits the clip under [timelineUs] at the source frame shown there. Both halves keep the
 * original speed domain, so playback timing is unchanged. No-op within [Clip.MIN_DURATION_US]
 * of a clip edge.
 */
fun Project.splitAt(timelineUs: Long, ids: IdSource = IdSource.Random): Project {
    val p = placementAt(timelineUs) ?: return this
    val c = p.clip
    if (p.localUs < Clip.MIN_DURATION_US || c.durationUs - p.localUs < Clip.MIN_DURATION_US) return this
    val cut = p.sourceUs
    if (cut <= c.sourceStartUs || cut >= c.sourceEndUs) return this
    val left = c.copy(sourceEndUs = cut)
    val right = c.copy(id = ids.next(), sourceStartUs = cut)
    return withClips(main.clips.toMutableList().apply { set(p.index, left); add(p.index + 1, right) })
}

/**
 * Sets the clip's source span. A constant-speed clip may grow to the whole file; a curved clip
 * stays inside its speed domain (the curve is undefined outside it).
 */
fun Project.trim(clipId: String, sourceStartUs: Long, sourceEndUs: Long): Project {
    val c = clip(clipId)
    val (lo, hi) = when (c.speed) {
        is Speed.Constant -> 0L to asset(c.assetId).durationUs
        is Speed.Curve -> c.speedDomainStartUs to c.speedDomainEndUs
    }
    val start = sourceStartUs.coerceIn(lo, hi)
    val end = sourceEndUs.coerceIn(lo, hi)
    require(end - start >= Clip.MIN_DURATION_US) { "trim leaves less than the minimum clip length" }
    if (start == c.sourceStartUs && end == c.sourceEndUs) return this
    val trimmed = when (c.speed) {
        is Speed.Constant -> c.copy(sourceStartUs = start, sourceEndUs = end, speedDomainStartUs = start, speedDomainEndUs = end)
        is Speed.Curve -> c.copy(sourceStartUs = start, sourceEndUs = end)
    }
    return replace(trimmed)
}

fun Project.removeClip(clipId: String): Project = withClips(main.clips.filterNot { it.id == clipId })

fun Project.moveClip(clipId: String, toIndex: Int): Project {
    val from = main.clips.indexOfFirst { it.id == clipId }
    require(from >= 0) { "no clip $clipId" }
    val to = toIndex.coerceIn(0, main.clips.lastIndex)
    if (from == to) return this
    return withClips(main.clips.toMutableList().apply { add(to, removeAt(from)) })
}

/** New speed for the clip; the curve's 0..1 now spans exactly the clip as it is now. */
fun Project.setSpeed(clipId: String, speed: Speed): Project {
    val c = clip(clipId)
    if (c.speed == speed && c.speedDomainStartUs == c.sourceStartUs && c.speedDomainEndUs == c.sourceEndUs) return this
    return replace(c.copy(speed = speed, speedDomainStartUs = c.sourceStartUs, speedDomainEndUs = c.sourceEndUs))
}

fun Project.setSmoothing(clipId: String, smoothing: Smoothing): Project {
    val c = clip(clipId)
    return if (c.smoothing == smoothing) this else replace(c.copy(smoothing = smoothing))
}

fun Project.setVolume(clipId: String, volume: Float): Project {
    val c = clip(clipId)
    val v = volume.coerceIn(0f, Clip.MAX_VOLUME)
    return if (c.volume == v) this else replace(c.copy(volume = v))
}

private fun Project.replace(clip: Clip): Project =
    withClips(main.clips.map { if (it.id == clip.id) clip else it })

private fun Project.withClips(clips: List<Clip>): Project {
    // Drop assets no clip references any more, so saved projects don't pin deleted media.
    val used = clips.mapTo(HashSet()) { it.assetId }
    return copy(main = main.copy(clips = clips), assets = assets.filter { it.id in used })
}
