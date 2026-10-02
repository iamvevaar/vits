package com.vits.engine.export

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Measures, on this device, how many samples late audio comes out of AAC encode → decode.
 *
 * Encoders prepend "priming" samples (1024, 2048, 2112… depending on vendor) and MediaMuxer
 * records no edit list to cancel them, so every player hears the soundtrack that much late (64 ms
 * on the emulator: visible lip-sync error). We encode a tone burst at a known offset with the same
 * encoder settings, decode it with the platform decoder players use, and find where it lands.
 */
internal object AacDelay {
    @Volatile private var cached: Int? = null

    fun samples(createEncoder: () -> MediaCodec, sampleRate: Int, channels: Int): Int =
        cached ?: runCatching { measure(createEncoder, sampleRate, channels) }.getOrDefault(FALLBACK)
            .also { cached = it }

    private fun measure(createEncoder: () -> MediaCodec, rate: Int, channels: Int): Int {
        val total = rate / 2
        val burstAt = rate / 10
        val pcm = ShortArray(total * channels) { i ->
            val f = i / channels
            if (f in burstAt until burstAt + rate / 10) (sin(2 * PI * 1000 * (f - burstAt) / rate) * 12000).toInt().toShort() else 0
        }
        val encoded = ArrayList<Pair<ByteArray, Long>>()
        var format: MediaFormat? = null
        val enc = createEncoder()
        val info = MediaCodec.BufferInfo()
        try {
            var queued = 0
            var done = false
            while (!done) {
                if (queued < total) {
                    val i = enc.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val buf = enc.getInputBuffer(i)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val n = minOf(buf.remaining() / channels, total - queued)
                        buf.put(pcm, queued * channels, n * channels)
                        queued += n
                        enc.queueInputBuffer(i, 0, n * channels * 2, queued * 1_000_000L / rate,
                            if (queued >= total) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = enc.outputFormat
                if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val b = enc.getOutputBuffer(o)!!
                        b.position(info.offset).limit(info.offset + info.size)
                        encoded += ByteArray(info.size).also { b.get(it) } to info.presentationTimeUs
                    }
                    done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    enc.releaseOutputBuffer(o, false)
                }
            }
        } finally {
            enc.stop()
            enc.release()
        }

        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val out = ArrayList<Short>()
        try {
            dec.configure(format!!, null, null, 0)
            dec.start()
            var next = 0
            var done = false
            while (!done) {
                if (next <= encoded.size) {
                    val i = dec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        if (next == encoded.size) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val (data, pts) = encoded[next]
                            dec.getInputBuffer(i)!!.put(data)
                            dec.queueInputBuffer(i, 0, data.size, pts, 0)
                        }
                        next++
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    val sb = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    sb.position(info.offset / 2).limit((info.offset + info.size) / 2)
                    while (sb.hasRemaining()) out += sb.get()
                    done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    dec.releaseOutputBuffer(o, false)
                }
            }
        } finally {
            dec.stop()
            dec.release()
        }
        val onset = (0 until out.size / channels).firstOrNull { abs(out[it * channels].toInt()) > 1500 }
            ?: return FALLBACK
        return (onset - burstAt).coerceIn(0, rate / 5)
    }

    private const val FALLBACK = 2048
}
