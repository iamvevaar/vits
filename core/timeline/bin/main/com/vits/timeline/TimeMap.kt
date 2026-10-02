package com.vits.timeline

/**
 * Bidirectional mapping between source-clip time and output (retimed) time.
 *
 * Output time is t_out(x) = D · ∫₀ˣ 1/s(u) du, where D is the source duration and s the speed.
 * For curves the integral is tabulated once (Simpson per cell) so every lookup is O(log N),
 * cheap enough to call per rendered frame and per audio hop.
 */
class TimeMap(val spec: SpeedSpec, val sourceDurationUs: Long) {
    private val d = sourceDurationUs.toDouble()

    // cumulative[i] = ∫₀^(i/N) 1/s(u) du ; only used for curves.
    private val cumulative: DoubleArray? = (spec as? SpeedSpec.Curve)?.let { curve ->
        DoubleArray(LUT_SIZE + 1).also { c ->
            val h = 1.0 / LUT_SIZE
            for (i in 0 until LUT_SIZE) {
                val a = i * h
                val f0 = 1.0 / curve.speedAt(a)
                val fm = 1.0 / curve.speedAt(a + h / 2)
                val f1 = 1.0 / curve.speedAt(a + h)
                c[i + 1] = c[i] + h / 6 * (f0 + 4 * fm + f1)
            }
        }
    }

    val outputDurationUs: Long = when (spec) {
        is SpeedSpec.Constant -> (d / spec.speed).toLong()
        is SpeedSpec.Curve -> (d * cumulative!![LUT_SIZE]).toLong()
    }

    /** Source time (µs) shown at output time [outputUs]. */
    fun sourceTimeAt(outputUs: Long): Long {
        if (outputUs <= 0) return 0
        if (outputUs >= outputDurationUs) return sourceDurationUs
        return when (spec) {
            is SpeedSpec.Constant -> (outputUs * spec.speed).toLong().coerceAtMost(sourceDurationUs)
            is SpeedSpec.Curve -> {
                val c = cumulative!!
                val target = outputUs / d
                var lo = 0
                var hi = LUT_SIZE
                while (hi - lo > 1) {
                    val mid = (lo + hi) ushr 1
                    if (c[mid] <= target) lo = mid else hi = mid
                }
                val frac = (target - c[lo]) / (c[hi] - c[lo])
                ((lo + frac) / LUT_SIZE * d).toLong()
            }
        }
    }

    /** Output time (µs) at which source time [sourceUs] is shown. */
    fun outputTimeAt(sourceUs: Long): Long {
        if (sourceUs <= 0) return 0
        if (sourceUs >= sourceDurationUs) return outputDurationUs
        return when (spec) {
            is SpeedSpec.Constant -> (sourceUs / spec.speed).toLong()
            is SpeedSpec.Curve -> {
                val c = cumulative!!
                val pos = sourceUs / d * LUT_SIZE
                val i = pos.toInt().coerceAtMost(LUT_SIZE - 1)
                val v = c[i] + (c[i + 1] - c[i]) * (pos - i)
                (v * d).toLong()
            }
        }
    }

    /** Instantaneous speed at source time [sourceUs]. */
    fun speedAtSource(sourceUs: Long): Double =
        spec.speedAt((sourceUs / d).coerceIn(0.0, 1.0))

    companion object {
        private const val LUT_SIZE = 4096
    }
}
