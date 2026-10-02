package com.vits.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Streaming sample-rate converter: windowed-sinc (Blackman) interpolation from a precomputed
 * polyphase table, with the cutoff lowered when downsampling so nothing aliases. Interleaved
 * float samples in, interleaved float samples out.
 */
class Resampler(
    private val inRate: Int,
    private val outRate: Int,
    private val channels: Int,
) {
    private val step = inRate.toDouble() / outRate          // input samples per output sample
    private val cutoff = minOf(1.0, outRate.toDouble() / inRate) * 0.95
    private val table = FloatArray(PHASES * TAPS)

    // History of the last TAPS input frames (plus incoming), per channel interleaved.
    private var buf = FloatArray(TAPS * channels * 4)
    private var bufFrames = 0
    private var pos = (TAPS / 2 - 1).toDouble()             // fractional read position in buf
    val passthrough = inRate == outRate

    init {
        val half = TAPS / 2
        for (p in 0 until PHASES) {
            val frac = p.toDouble() / PHASES
            var sum = 0.0
            for (k in 0 until TAPS) {
                val x = k - (half - 1) - frac                 // distance from the read point
                val sinc = if (x == 0.0) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
                val w = 0.42 + 0.5 * cos(PI * x / half) + 0.08 * cos(2 * PI * x / half)
                val v = cutoff * sinc * w
                table[p * TAPS + k] = v.toFloat()
                sum += v
            }
            // Normalise each phase to unity DC gain.
            for (k in 0 until TAPS) table[p * TAPS + k] = (table[p * TAPS + k] / sum).toFloat()
        }
        // Prime with silence so the first output sample is centred on the first input sample.
        bufFrames = TAPS / 2 - 1
    }

    /** Converts [frames] input frames from [input]; returns output written into a fresh array. */
    fun process(input: FloatArray, frames: Int): FloatArray {
        if (passthrough) return input.copyOf(frames * channels)
        append(input, frames)
        return drain(flush = false)
    }

    /** Emits the tail held back for the filter's look-ahead. */
    fun finish(): FloatArray {
        if (passthrough) return FloatArray(0)
        append(FloatArray(TAPS * channels), TAPS)
        return drain(flush = true)
    }

    private fun append(input: FloatArray, frames: Int) {
        val need = (bufFrames + frames) * channels
        if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size * 2))
        input.copyInto(buf, bufFrames * channels, 0, frames * channels)
        bufFrames += frames
    }

    private fun drain(flush: Boolean): FloatArray {
        val half = TAPS / 2
        val limit = bufFrames - half                         // need half taps of look-ahead
        val count = maxOf(0, kotlin.math.ceil((limit - pos) / step).toInt())
        val out = FloatArray(count * channels)
        for (i in 0 until count) {
            val base = floor(pos).toInt()
            val phase = ((pos - base) * PHASES).toInt().coerceIn(0, PHASES - 1)
            val first = base - (half - 1)
            val t = phase * TAPS
            for (c in 0 until channels) {
                var acc = 0f
                var idx = first * channels + c
                for (k in 0 until TAPS) {
                    acc += table[t + k] * buf[idx]
                    idx += channels
                }
                out[i * channels + c] = acc
            }
            pos += step
        }
        // Keep only what future outputs can still reach.
        val keepFrom = maxOf(0, floor(pos).toInt() - (half - 1))
        if (keepFrom > 0 && !flush) {
            buf.copyInto(buf, 0, keepFrom * channels, bufFrames * channels)
            bufFrames -= keepFrom
            pos -= keepFrom
        }
        return out
    }

    private companion object {
        const val TAPS = 32
        const val PHASES = 512
    }
}
