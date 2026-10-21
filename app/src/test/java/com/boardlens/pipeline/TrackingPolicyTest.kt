package com.boardlens.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingPolicyTest {

    /** Fake view: which frame it was built from, so tests can tell anchors apart. */
    private data class FakeView(val frame: Int)

    private val start = Quad.inset(0.2f)
    private fun shifted(dx: Float) = start.map { Pt(it.x + dx, it.y) }

    private var released = mutableListOf<FakeView>()
    private fun policy() = TrackingPolicy<FakeView>(promoteAfter = 3) { released += it }

    @Test
    fun offUntilReset() {
        val p = policy()
        assertEquals(null, p.step({ start }, { FakeView(1) }))
        assertEquals(TrackingStatus.Off, p.status)
    }

    @Test
    fun anchorMatchWins() {
        val p = policy()
        p.reset(FakeView(0), start)
        val q = p.step(match = { ref -> if (ref.frame == 0) shifted(0.05f) else null }, viewAt = { FakeView(1) })
        assertEquals(shifted(0.05f), q)
        assertEquals(TrackingStatus.Anchor, p.status)
        assertEquals(FakeView(0), p.anchor)  // anchor never drifts while it keeps matching
    }

    @Test
    fun slideChangeHoldsThenTracksIncrementallyThenPromotes() {
        val p = policy()
        p.reset(FakeView(0), start)
        // Frame 1: content changed, nothing matches -> hold.
        assertEquals(start, p.step({ null }, { FakeView(1) }))
        assertEquals(TrackingStatus.Lost, p.status)
        assertEquals(1, p.consecutiveLost)

        // Frames 2-4: old anchor fails, previous frame (new slide) matches.
        for (f in 2..4) {
            p.step({ ref -> if (ref.frame == f - 1) shifted(0.01f * f) else null }, { FakeView(f) })
            assertEquals(TrackingStatus.Incremental, p.status)
        }
        // Third incremental success promotes the newest view to anchor.
        assertEquals(FakeView(4), p.anchor)
        assertEquals(1, p.promotions)
        assertEquals(0, p.consecutiveLost)
        assertTrue(FakeView(0) in released)

        // Frame 5: new anchor matches directly.
        p.step({ ref -> if (ref.frame == 4) shifted(0.05f) else null }, { FakeView(5) })
        assertEquals(TrackingStatus.Anchor, p.status)
    }

    @Test
    fun anchorRecoveryResetsStreak() {
        val p = policy()
        p.reset(FakeView(0), start)
        p.step({ ref -> if (ref.frame == 0) null else start }, { FakeView(1) }) // lost (prev == anchor)
        p.step({ ref -> if (ref.frame == 1) start else null }, { FakeView(2) }) // incremental 1
        p.step({ ref -> if (ref.frame == 2) start else null }, { FakeView(3) }) // incremental 2
        p.step({ ref -> if (ref.frame == 0) start else null }, { FakeView(4) }) // anchor again
        p.step({ ref -> if (ref.frame == 4) start else null }, { FakeView(5) }) // incremental 1
        assertEquals(FakeView(0), p.anchor)  // streak was reset, no promotion
        assertEquals(0, p.promotions)
    }

    @Test
    fun neverReleasesLiveViews() {
        val p = policy()
        p.reset(FakeView(0), start)
        for (f in 1..10) p.step({ ref -> if (ref.frame == f - 1) start else null }, { FakeView(f) })
        val live = setOfNotNull(p.anchor, p.previous)
        assertFalse(released.any { it in live })
        // Every view except the live ones was released exactly once.
        assertEquals(11 - live.size, released.size)
        assertEquals(released.size, released.toSet().size)
    }

    @Test
    fun sharpnessGateSkipsBlurButNeverStalls() {
        val g = SharpnessGatePolicy(ratio = 0.6, window = 8, maxSkips = 2)
        repeat(5) { assertTrue(g.accept(100.0)) }
        assertFalse(g.accept(30.0))   // much blurrier than recent median
        assertFalse(g.accept(30.0))
        assertTrue(g.accept(30.0))    // third in a row goes through anyway
        assertTrue(g.accept(95.0))
    }

    @Test
    fun sharpnessGateAdaptsToNewContent() {
        val g = SharpnessGatePolicy(ratio = 0.6, window = 4, maxSkips = 2)
        repeat(4) { g.accept(100.0) }
        // New slide with less text: genuinely lower sharpness for good.
        val accepted = (1..8).map { g.accept(40.0) }
        assertTrue(accepted.takeLast(4).all { it })
    }
}
