package com.vits.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private fun run(inRate: Int, outRate: Int, freq: Double, seconds: Double): FloatArray {
        val n = (inRate * seconds).toInt()
        val input = FloatArray(n) { (0.5 * sin(2 * PI * freq * it / inRate)).toFloat() }
        val r = Resampler(inRate, outRate, 1)
        val parts = ArrayList<FloatArray>()
        var i = 0
        while (i < n) {
            val c = minOf(1000, n - i)
            parts += r.process(input.copyOfRange(i, i + c), c)
            i += c
        }
        parts += r.finish()
        return parts.fold(FloatArray(0)) { a, b -> a + b }
    }

    private fun zeroCrossingRate(s: FloatArray, rate: Int): Double {
        var z = 0
        for (i in rate / 10 + 1 until s.size - rate / 10) if ((s[i - 1] < 0) != (s[i] < 0)) z++
        return z / 2.0 / ((s.size - rate / 5).toDouble() / rate)
    }

    private fun rms(s: FloatArray, from: Int, to: Int) = sqrt((from until to).sumOf { s[it].toDouble() * s[it] } / (to - from))

    @Test fun upsampleKeepsPitchLengthAndLevel() {
        val out = run(44_100, 48_000, 1000.0, 2.0)
        assertTrue("len ${out.size}", abs(out.size - 96_000) < 64)
        assertTrue(abs(zeroCrossingRate(out, 48_000) - 1000.0) < 3)
        assertTrue(abs(rms(out, 4800, 90_000) - 0.5 / sqrt(2.0)) < 0.01)
    }

    @Test fun downsampleRejectsAliases() {
        // 20 kHz at 48 kHz cannot exist at 22.05 kHz: it must be filtered out, not folded down.
        val out = run(48_000, 22_050, 20_000.0, 1.0)
        assertTrue("alias rms ${rms(out, 2205, out.size - 2205)}", rms(out, 2205, out.size - 2205) < 0.01)
    }

    @Test fun passThroughAtEqualRates() {
        val out = run(48_000, 48_000, 440.0, 0.5)
        assertTrue(out.size == 24_000)
    }
}
