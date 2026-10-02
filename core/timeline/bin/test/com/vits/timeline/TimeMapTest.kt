package com.vits.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TimeMapTest {
    private val tenSec = 10_000_000L

    @Test fun constantHalfSpeedDoublesDuration() {
        val map = TimeMap(SpeedSpec.Constant(0.5), tenSec)
        assertEquals(20_000_000L, map.outputDurationUs)
        assertEquals(5_000_000L, map.sourceTimeAt(10_000_000L))
    }

    @Test fun flatCurveMatchesConstant() {
        val map = TimeMap(SpeedSpec.Curve(listOf(SpeedPoint(0.0, 2.0), SpeedPoint(1.0, 2.0))), tenSec)
        assertTrue(abs(map.outputDurationUs - 5_000_000L) < 10)
    }

    @Test fun curveMappingRoundTrips() {
        SpeedPreset.entries.forEach { preset ->
            val map = TimeMap(preset.curve(), tenSec)
            for (i in 0..100) {
                val src = tenSec * i / 100
                val back = map.sourceTimeAt(map.outputTimeAt(src))
                assertTrue("${preset.name} at $src -> $back", abs(back - src) < 2_000)
            }
        }
    }

    @Test fun sourceTimeIsMonotonic() {
        val map = TimeMap(SpeedPreset.BULLET.curve(), tenSec)
        var prev = -1L
        for (i in 0..10_000) {
            val s = map.sourceTimeAt(map.outputDurationUs * i / 10_000)
            assertTrue(s >= prev)
            prev = s
        }
    }

    @Test fun smoothstepHasNoOvershoot() {
        val curve = SpeedPreset.HERO.curve()
        for (i in 0..1000) {
            val s = curve.speedAt(i / 1000.0)
            assertTrue(s in 0.25 - 1e-9..5.0 + 1e-9)
        }
    }
}
