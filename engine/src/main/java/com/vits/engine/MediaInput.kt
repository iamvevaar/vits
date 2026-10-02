package com.vits.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.vits.project.MediaAsset

/** A media file. Every pipeline opens its own extractor so they can run independently. */
class MediaInput(private val context: Context, val uri: Uri) {
    constructor(context: Context, asset: MediaAsset) : this(context, Uri.parse(asset.uri))

    internal fun newExtractor() = MediaExtractor().apply { setDataSource(context, uri, null) }

    /** True if the file can still be opened (it may have been deleted or its permission revoked). */
    fun isReadable(): Boolean = runCatching { newExtractor().release() }.isSuccess

    /** Reads everything the engine needs about the file; [id] becomes the asset's identity. */
    fun probe(id: String): MediaAsset {
        val ex = newExtractor()
        try {
            var video = -1
            var audio = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (video < 0 && mime.startsWith("video/")) video = i
                if (audio < 0 && mime.startsWith("audio/")) audio = i
            }
            require(video >= 0) { "No video track found" }
            val f = ex.getTrackFormat(video)

            // Presentation of the first frame (B-frames make decode order differ, so take a min).
            ex.selectTrack(video)
            val times = ArrayList<Long>()
            repeat(90) {
                val t = ex.sampleTime
                if (t < 0) return@repeat
                times += t
                ex.advance()
            }
            val origin = times.take(30).minOrNull() ?: 0L

            // Measured spacing beats KEY_FRAME_RATE, which some extractors derive as
            // frames / container duration (wrong whenever an edit list pads the track).
            val fps = snapFps(
                estimateFps(times) ?: f.floatOrNull(MediaFormat.KEY_FRAME_RATE)?.takeIf { it in 1f..480f } ?: 30f
            )

            val retriever = MediaMetadataRetriever()
            val rotation = try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
            } catch (_: Exception) {
                f.intOrNull(MediaFormat.KEY_ROTATION) ?: 0
            } finally {
                retriever.release()
            }

            return MediaAsset(
                id = id,
                uri = uri.toString(),
                durationUs = f.longOrNull(MediaFormat.KEY_DURATION) ?: error("Video duration unknown"),
                width = f.getInteger(MediaFormat.KEY_WIDTH),
                height = f.getInteger(MediaFormat.KEY_HEIGHT),
                rotation = ((rotation % 360) + 360) % 360,
                frameRate = fps,
                videoTrack = video,
                audioTrack = audio,
                originUs = origin,
            )
        } finally {
            ex.release()
        }
    }

    /** Frame rate from the median presentation interval, robust to B-frames and dropped frames. */
    private fun estimateFps(times: List<Long>): Float? {
        val sorted = times.sorted()
        if (sorted.size < 10) return null
        val intervals = sorted.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        if (intervals.isEmpty()) return null
        return 1_000_000f / intervals[intervals.size / 2]
    }

    private fun snapFps(fps: Float): Float {
        val nearest = STANDARD_RATES.minBy { kotlin.math.abs(fps - it) }
        return if (kotlin.math.abs(fps - nearest) / nearest < 0.03f) nearest else fps
    }

    private companion object {
        val STANDARD_RATES = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f, 90f, 120f, 240f)
    }
}

internal fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

internal fun MediaFormat.longOrNull(key: String): Long? =
    if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

internal fun MediaFormat.floatOrNull(key: String): Float? {
    if (!containsKey(key)) return null
    return runCatching { getFloat(key) }.getOrNull() ?: runCatching { getInteger(key).toFloat() }.getOrNull()
}
