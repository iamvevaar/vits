package com.vits.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class WsolaTimeStretcherTest {
    private val sr = 48_000

    private fun stretch(input: ShortArray, channels: Int, speed: (Long) -> Double): ShortArray {
        val out = ArrayList<Short>()
        val ts = WsolaTimeStretcher(sr, channels, speed) { pcm, frames ->
            for (i in 0 until frames * channels) out.add(pcm[i])
        }
        val chunk = 1000 * channels
        var i = 0
        while (i < input.size) {
            val n = minOf(chunk, input.size - i)
            ts.queue(input.copyOfRange(i, i + n), n / channels)
            i += n
        }
        ts.finish()
        return out.toShortArray()
    }

    private fun sine(freq: Double, seconds: Double) =
        ShortArray((sr * seconds).toInt()) { (sin(2 * PI * freq * it / sr) * 16000).toInt().toShort() }

    private fun zeroCrossingsPerSecond(s: ShortArray, from: Int, to: Int): Double {
        var n = 0
        for (i in from + 1 until to) if ((s[i - 1] < 0) != (s[i] < 0)) n++
        return n / ((to - from).toDouble() / sr)
    }

    @Test fun halfSpeedDoublesLengthAndKeepsPitch() {
        val input = sine(440.0, 2.0)
        val out = stretch(input, 1) { 0.5 }
        val ratio = out.size.toDouble() / input.size
        assertTrue("ratio $ratio", abs(ratio - 2.0) < 0.03)
        val f = zeroCrossingsPerSecond(out, sr / 2, out.size - sr / 2) / 2
        assertTrue("freq $f", abs(f - 440.0) < 5)
    }

    @Test fun doubleSpeedHalvesLength() {
        val input = sine(300.0, 4.0)
        val out = stretch(input, 1) { 2.0 }
        val ratio = out.size.toDouble() / input.size
        assertTrue("ratio $ratio", abs(ratio - 0.5) < 0.03)
    }

    @Test fun unitySpeedIsNearlyTransparent() {
        val input = sine(500.0, 1.0)
        val out = stretch(input, 1) { 1.0 }
        var maxErr = 0
        for (i in 2000 until input.size - 4000) maxErr = maxOf(maxErr, abs(out[i] - input[i]))
        assertTrue("maxErr $maxErr", maxErr < 200)
    }

    @Test fun stereoKeepsChannelCount() {
        val mono = sine(440.0, 1.0)
        val stereo = ShortArray(mono.size * 2) { mono[it / 2] }
        val out = stretch(stereo, 2) { 0.7 }
        assertEquals(0, out.size % 2)
    }
}
