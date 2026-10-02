package com.vits.timeline

import kotlin.math.exp
import kotlin.math.ln

/** A control point on a speed curve. [x] is normalized position in the source clip (0..1). */
data class SpeedPoint(val x: Double, val speed: Double)

/** How playback speed varies across a clip. */
sealed interface SpeedSpec {
    /** Speed multiplier at normalized source position [x] in 0..1. */
    fun speedAt(x: Double): Double

    data class Constant(val speed: Double) : SpeedSpec {
        init {
            require(speed in MIN_SPEED..MAX_SPEED) { "speed $speed out of range" }
        }

        override fun speedAt(x: Double) = speed
    }

    /**
     * Piecewise curve through [points]. Between neighbours, speed eases with a smoothstep in
     * log-space: segments are monotone (no overshoot), flat at every control point, and 0.5x→2x
     * is perceived as symmetric with 2x→0.5x.
     */
    class Curve(points: List<SpeedPoint>) : SpeedSpec {
        val points: List<SpeedPoint> = points.sortedBy { it.x }
        private val logSpeeds = this.points.map { ln(it.speed) }.toDoubleArray()

        init {
            require(this.points.size >= 2) { "a curve needs at least 2 points" }
            require(this.points.first().x == 0.0 && this.points.last().x == 1.0) {
                "curve must span x = 0..1"
            }
            require(this.points.all { it.speed in MIN_CURVE_SPEED..MAX_CURVE_SPEED })
        }

        override fun speedAt(x: Double): Double {
            val p = points
            if (x <= 0.0) return p.first().speed
            if (x >= 1.0) return p.last().speed
            var i = 1
            while (p[i].x < x) i++
            val x0 = p[i - 1].x
            val x1 = p[i].x
            if (x1 <= x0) return p[i].speed
            val t = (x - x0) / (x1 - x0)
            val s = t * t * (3 - 2 * t)
            return exp(logSpeeds[i - 1] + (logSpeeds[i] - logSpeeds[i - 1]) * s)
        }

        fun withPoints(points: List<SpeedPoint>) = Curve(points)

        override fun equals(other: Any?) = other is Curve && other.points == points
        override fun hashCode() = points.hashCode()
        override fun toString() = "Curve($points)"
    }

    /**
     * The part [x0]..[x1] of [base], stretched over 0..1. Trimmed or split clips use this so every
     * source instant keeps exactly the speed it had before the edit.
     */
    data class Windowed(val base: SpeedSpec, val x0: Double, val x1: Double) : SpeedSpec {
        init {
            require(0.0 <= x0 && x0 < x1 && x1 <= 1.0) { "bad window $x0..$x1" }
        }

        override fun speedAt(x: Double) = base.speedAt(x0 + x.coerceIn(0.0, 1.0) * (x1 - x0))
    }

    companion object {
        const val MIN_SPEED = 0.1
        const val MAX_SPEED = 100.0
        const val MIN_CURVE_SPEED = 0.1
        const val MAX_CURVE_SPEED = 10.0
    }
}

/** True when any part of the clip plays slower than real time, i.e. frames must be synthesized. */
fun SpeedSpec.hasSlowMotion(): Boolean = when (this) {
    is SpeedSpec.Constant -> speed < 1.0
    else -> (0..256).any { speedAt(it / 256.0) < 0.999 }
}

/** Collapses windows over constants, which keeps the exact constant-speed path in [TimeMap]. */
fun SpeedSpec.simplified(): SpeedSpec = when (this) {
    is SpeedSpec.Windowed -> when (val b = base.simplified()) {
        is SpeedSpec.Constant -> b
        is SpeedSpec.Windowed -> SpeedSpec.Windowed(b.base, b.x0 + x0 * (b.x1 - b.x0), b.x0 + x1 * (b.x1 - b.x0))
        else -> if (x0 == 0.0 && x1 == 1.0) b else SpeedSpec.Windowed(b, x0, x1)
    }
    else -> this
}
