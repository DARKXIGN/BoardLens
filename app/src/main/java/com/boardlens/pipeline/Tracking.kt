package com.boardlens.pipeline

/*
 * Pure decision logic for board tracking and frame gating. The OpenCV parts
 * (features, matching, homography) live in RegionTracker.kt and are plugged in
 * as functions, so these rules run in JVM unit tests.
 *
 * Reference implementation and simulation: tools/tracking/.
 */

enum class TrackingStatus {
    /** No region selected. */
    Off,
    /** Matched against the long-term anchor (the user's selection, or a promoted new slide). */
    Anchor,
    /** Anchor failed, matched against the previous frame (typical right after a slide change). */
    Incremental,
    /** Nothing matched; holding the last position. */
    Lost,
}

/**
 * Anchor + frame-to-frame tracking policy.
 *
 * - Try the long-term anchor first: it does not drift.
 * - If it fails (new slide content), match the previous frame: consecutive frames
 *   0.4 s apart share content even right after a change.
 * - After [promoteAfter] consecutive frame-to-frame successes, the change is real:
 *   promote the current view to be the new anchor.
 * - If both fail, hold the last quad and keep going from this frame.
 *
 * [V] is an opaque "view" (features of one frame around a quad). [release] frees
 * views that are no longer referenced (OpenCV Mats).
 */
class TrackingPolicy<V : Any>(
    private val promoteAfter: Int = 3,
    private val release: (V) -> Unit = {},
) {
    var anchor: V? = null
        private set
    var previous: V? = null
        private set
    /** Current board quad, in whatever coordinates the matcher uses. */
    var quad: Quad? = null
        private set
    var status: TrackingStatus = TrackingStatus.Off
        private set
    var consecutiveLost: Int = 0
        private set
    var promotions: Int = 0
        private set

    private var incrementalStreak = 0

    fun reset(view: V?, quad: Quad?) {
        releaseAll()
        anchor = view
        previous = view
        this.quad = if (view == null) null else quad
        status = if (view == null || quad == null) TrackingStatus.Off else TrackingStatus.Anchor
        incrementalStreak = 0
        consecutiveLost = 0
    }

    /**
     * One tracking step.
     * [match] estimates the quad in the current frame from a reference view (null = no reliable match).
     * [viewAt] builds the current frame's view around a quad.
     * Returns the quad to use, or null when tracking is off.
     */
    fun step(match: (V) -> Quad?, viewAt: (Quad) -> V): Quad? {
        val a = anchor ?: return null
        val p = previous ?: a
        val held = quad ?: return null

        var next = match(a)
        val newStatus: TrackingStatus
        if (next != null) {
            newStatus = TrackingStatus.Anchor
            incrementalStreak = 0
        } else {
            next = if (p !== a) match(p) else null
            if (next != null) {
                newStatus = TrackingStatus.Incremental
                incrementalStreak++
            } else {
                newStatus = TrackingStatus.Lost
                next = held
            }
        }

        val current = viewAt(next)
        if (p !== a) release(p)
        previous = current
        quad = next
        status = newStatus
        consecutiveLost = if (newStatus == TrackingStatus.Lost) consecutiveLost + 1 else 0

        if (incrementalStreak >= promoteAfter) {
            release(a)
            anchor = current
            incrementalStreak = 0
            promotions++
        }
        return next
    }

    fun releaseAll() {
        val a = anchor
        val p = previous
        if (p != null && p !== a) release(p)
        if (a != null) release(a)
        anchor = null
        previous = null
    }
}

/**
 * Skips frames much blurrier than recent ones (hand motion during exposure).
 * Blurry frames hurt both tracking and OCR; never skips more than [maxSkips]
 * in a row so a dim, uniformly soft scene cannot stall the app.
 * Compares against the median of the last [window] frames, not a fixed
 * reference, because a new slide with less text is legitimately less "sharp".
 */
class SharpnessGatePolicy(
    private val ratio: Double = 0.6,
    private val window: Int = 8,
    private val maxSkips: Int = 2,
) {
    private val history = ArrayDeque<Double>()
    private var skips = 0

    fun accept(sharpness: Double): Boolean {
        val recent = if (history.isEmpty()) sharpness else median(history)
        history.addLast(sharpness)
        while (history.size > window) history.removeFirst()
        if (sharpness < ratio * recent && skips < maxSkips) {
            skips++
            return false
        }
        skips = 0
        return true
    }

    fun reset() {
        history.clear()
        skips = 0
    }

    private fun median(values: Collection<Double>): Double {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }
}
