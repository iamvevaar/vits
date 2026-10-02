package com.vits.project

import com.vits.timeline.SpeedPoint
import com.vits.timeline.SpeedSpec
import com.vits.timeline.TimeMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An editing project: immutable, serializable, and free of Android types so every edit is a pure,
 * unit-testable function. Times are microseconds. "Source" time is inside a media file; "timeline"
 * time is in the edited result.
 */
@Serializable
data class Project(
    val schemaVersion: Int = ProjectCodec.SCHEMA_VERSION,
    val id: String,
    val name: String = "",
    val assets: List<MediaAsset> = emptyList(),
    val main: Track = Track(),
    /** Output frame; null follows the first clip's media, the usual "original" setting. */
    val canvas: Canvas? = null,
) {
    fun asset(id: String): MediaAsset = assets.first { it.id == id }

    val durationUs: Long get() = main.clips.sumOf { it.durationUs }

    /** The frame size and rate the result is rendered at. */
    val resolvedCanvas: Canvas
        get() = canvas ?: main.clips.firstOrNull()?.let { c ->
            asset(c.assetId).let { Canvas(it.displayWidth, it.displayHeight, it.nominalFps) }
        } ?: Canvas(1920, 1080, 30)
}

@Serializable
data class Canvas(val width: Int, val height: Int, val fps: Int)

/** A media file and what probing it found; clips reference it by [id]. */
@Serializable
data class MediaAsset(
    val id: String,
    val uri: String,
    val durationUs: Long,
    /** Coded (unrotated) frame size. */
    val width: Int,
    val height: Int,
    /** Clockwise rotation for display. */
    val rotation: Int,
    val frameRate: Float,
    /** Track indices inside the file; audioTrack is -1 when there is no audio. */
    val videoTrack: Int = 0,
    val audioTrack: Int = -1,
    /** Presentation time of the first frame; source time 0 is this pts. */
    val originUs: Long = 0,
) {
    val hasAudio get() = audioTrack >= 0
    val displayWidth get() = if (rotation % 180 == 0) width else height
    val displayHeight get() = if (rotation % 180 == 0) height else width
    val nominalFps get() = Math.round(frameRate).coerceIn(24, 60)
}

/** The magnetic main track: clips play back to back with no gaps. */
@Serializable
data class Track(val clips: List<Clip> = emptyList())

@Serializable
enum class Smoothing { OFF, FRAME_BLENDING, OPTICAL_FLOW }

@Serializable
sealed interface Speed {
    fun toSpec(): SpeedSpec

    @Serializable
    @SerialName("constant")
    data class Constant(val speed: Double) : Speed {
        override fun toSpec() = SpeedSpec.Constant(speed)
    }

    @Serializable
    @SerialName("curve")
    data class Curve(
        val points: List<Point>,
        /** Which preset it came from, so the editor can show it selected; null for custom. */
        val preset: String? = null,
    ) : Speed {
        override fun toSpec() = SpeedSpec.Curve(points.map { SpeedPoint(it.x, it.speed) })
    }

    @Serializable
    data class Point(val x: Double, val speed: Double)
}

/**
 * A span [sourceStartUs]..[sourceEndUs] of an asset placed on the timeline.
 *
 * [speed] is defined over the source span [speedDomainStartUs]..[speedDomainEndUs], not over the
 * clip itself: trimming or splitting changes the clip span but never the domain, so every source
 * instant keeps exactly the speed it had. Splitting a curved clip therefore moves no frame.
 */
@Serializable
data class Clip(
    val id: String,
    val assetId: String,
    val sourceStartUs: Long,
    val sourceEndUs: Long,
    val speed: Speed = Speed.Constant(1.0),
    val speedDomainStartUs: Long = sourceStartUs,
    val speedDomainEndUs: Long = sourceEndUs,
    val smoothing: Smoothing = Smoothing.FRAME_BLENDING,
    /** Linear gain; 0 mutes. */
    val volume: Float = 1f,
) {
    init {
        require(sourceStartUs in 0 until sourceEndUs) { "empty clip $sourceStartUs..$sourceEndUs" }
        require(speedDomainStartUs <= sourceStartUs && sourceEndUs <= speedDomainEndUs) {
            "speed domain $speedDomainStartUs..$speedDomainEndUs must cover $sourceStartUs..$sourceEndUs"
        }
        require(volume in 0f..MAX_VOLUME)
    }

    val sourceDurationUs: Long get() = sourceEndUs - sourceStartUs

    /** Speed over this clip's own 0..1 (its span inside the speed domain). */
    val speedSpec: SpeedSpec by lazy {
        val domain = (speedDomainEndUs - speedDomainStartUs).toDouble()
        SpeedSpec.Windowed(
            speed.toSpec(),
            (sourceStartUs - speedDomainStartUs) / domain,
            (sourceEndUs - speedDomainStartUs) / domain,
        )
    }

    /** Maps clip-local timeline time ↔ source time relative to [sourceStartUs]. */
    val timeMap: TimeMap by lazy { TimeMap(speedSpec, sourceDurationUs) }

    val durationUs: Long get() = timeMap.outputDurationUs

    companion object {
        const val MAX_VOLUME = 2f
        /** Shortest clip an edit may produce (one frame at 30 fps is 33 ms). */
        const val MIN_DURATION_US = 100_000L
    }
}
