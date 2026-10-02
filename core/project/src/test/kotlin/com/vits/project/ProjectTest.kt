package com.vits.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ProjectTest {
    private var n = 0
    private val ids = IdSource { "id${n++}" }
    private val tenSec = MediaAsset("a", "file:///a.mp4", 10_000_000, 1920, 1080, 0, 30f, audioTrack = 1)
    private val fourSec = MediaAsset("b", "file:///b.mp4", 4_000_000, 1080, 1920, 90, 60f)
    private val bullet = Speed.Curve(
        listOf(0.0 to 5.0, 0.3 to 5.0, 0.45 to 0.2, 0.55 to 0.2, 0.7 to 5.0, 1.0 to 5.0).map { Speed.Point(it.first, it.second) },
    )

    private fun project() = Project(id = "p").appendClip(tenSec, ids)

    @Test fun appendedClipsPlayBackToBack() {
        val p = project().appendClip(fourSec, ids)
        assertEquals(14_000_000, p.durationUs)
        assertEquals(1, p.placementAt(10_500_000)!!.index)
        assertEquals(500_000, p.placementAt(10_500_000)!!.sourceUs)
        assertEquals(4_000_000, p.placementAt(p.durationUs)!!.sourceUs)
    }

    @Test fun splittingACurvedClipMovesNoFrame() {
        val base = project().let { it.setSpeed(it.main.clips[0].id, bullet) }
        val split = base.splitAt(base.durationUs / 3, ids).let { it.splitAt(it.durationUs * 2 / 3, ids) }
        assertEquals(3, split.main.clips.size)
        assertTrue(abs(split.durationUs - base.durationUs) < 2_000)
        for (i in 0..200) {
            val t = base.durationUs * i / 200
            val before = base.placementAt(t)!!.sourceUs
            val after = split.placementAt(t)!!.sourceUs
            assertTrue("t=$t before=$before after=$after", abs(before - after) < 3_000)
        }
    }

    @Test fun splitHalvesAbutInSource() {
        val p = project().splitAt(4_000_000, ids)
        val (l, r) = p.main.clips
        assertEquals(l.sourceEndUs, r.sourceStartUs)
        assertEquals(4_000_000, l.sourceEndUs)
    }

    @Test fun splitNearAnEdgeIsANoOp() {
        val p = project()
        assertSame(p, p.splitAt(50_000, ids))
        assertSame(p, p.splitAt(p.durationUs - 50_000, ids))
    }

    @Test fun trimmingKeepsSpeedsAtEverySourceInstant() {
        val base = project().let { it.setSpeed(it.main.clips[0].id, bullet) }
        val c = base.main.clips[0]
        val trimmed = base.trim(c.id, 2_000_000, 8_000_000)
        val tc = trimmed.main.clips[0]
        for (s in listOf(2_500_000L, 4_500_000L, 5_000_000L, 7_900_000L)) {
            val a = c.timeMap.speedAtSource(s - c.sourceStartUs)
            val b = tc.timeMap.speedAtSource(s - tc.sourceStartUs)
            assertEquals(a, b, 1e-6)
        }
    }

    @Test fun curvedClipsCannotTrimPastTheirCurve() {
        val base = project().let { it.trim(it.main.clips[0].id, 2_000_000, 6_000_000) }
        val curved = base.setSpeed(base.main.clips[0].id, bullet)
        val c = curved.trim(curved.main.clips[0].id, 0, 10_000_000).main.clips[0]
        assertEquals(2_000_000, c.sourceStartUs)
        assertEquals(6_000_000, c.sourceEndUs)
    }

    @Test fun removingTheLastClipOfAnAssetDropsTheAsset() {
        val p = project().appendClip(fourSec, ids)
        val q = p.removeClip(p.main.clips[1].id)
        assertEquals(listOf("a"), q.assets.map { it.id })
    }

    @Test fun moveReorders() {
        val p = project().appendClip(fourSec, ids)
        val q = p.moveClip(p.main.clips[1].id, 0)
        assertEquals(listOf("b", "a"), q.main.clips.map { it.assetId })
        assertEquals(4_000_000, q.startOf(q.main.clips[1].id))
    }

    @Test fun timelineTimeOfInvertsPlacement() {
        val p = project().let { it.setSpeed(it.main.clips[0].id, bullet) }.appendClip(fourSec, ids)
        for (clip in p.main.clips) {
            for (s in listOf(clip.sourceStartUs + 100_000, (clip.sourceStartUs + clip.sourceEndUs) / 2)) {
                val t = p.timelineTimeOf(clip.id, s)
                assertTrue(abs(p.placementAt(t)!!.sourceUs - s) < 3_000)
            }
        }
    }

    @Test fun canvasFollowsFirstClip() {
        assertEquals(Canvas(1920, 1080, 30), project().resolvedCanvas)
        val portraitFirst = Project(id = "p").appendClip(fourSec, ids)
        assertEquals(Canvas(1920, 1080, 60), portraitFirst.resolvedCanvas)
    }
}

class HistoryTest {
    @Test fun undoRedo() {
        val h = History(1).record(2).record(3)
        assertEquals(2, h.undo().present)
        assertEquals(1, h.undo().undo().present)
        assertEquals(3, h.undo().undo().redo().redo().present)
        assertFalse(h.undo().record(9).canRedo)
    }

    @Test fun gesturesCoalesceIntoOneStep() {
        var h = History(0)
        for (v in 1..50) h = h.record(v, coalesceKey = "drag")
        h = h.seal().record(100, coalesceKey = "drag")
        assertEquals(50, h.undo().present)
        assertEquals(0, h.undo().undo().present)
    }

    @Test fun noOpsAreNotRecorded() {
        val h = History(1).record(1)
        assertFalse(h.canUndo)
    }
}

class ProjectCodecTest {
    @Test fun roundTrips() {
        var n = 0
        val ids = IdSource { "id${n++}" }
        val asset = MediaAsset("a", "content://media/1", 5_000_000, 1280, 720, 0, 29.97f, audioTrack = 1)
        val p = Project(id = "p", name = "Trip").appendClip(asset, ids).let {
            it.setSpeed(it.main.clips[0].id, Speed.Curve(listOf(Speed.Point(0.0, 1.0), Speed.Point(1.0, 0.25))))
        }.splitAt(2_000_000, ids).let { it.setSmoothing(it.main.clips[1].id, Smoothing.OPTICAL_FLOW) }
        val text = ProjectCodec.encode(p)
        assertEquals(p, ProjectCodec.decode(text))
        assertTrue(text.contains("\"schemaVersion\":1"))
    }

    @Test(expected = UnsupportedProjectVersionException::class)
    fun refusesNewerFormats() {
        ProjectCodec.decode("""{"schemaVersion":999,"id":"p"}""")
    }

    @Test fun ignoresUnknownFieldsFromMinorAdditions() {
        val p = ProjectCodec.decode("""{"schemaVersion":1,"id":"p","futureThing":{"x":1}}""")
        assertEquals("p", p.id)
    }
}
