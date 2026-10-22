package com.boardlens.pipeline

/**
 * Shows a line only once it has been read in more than one recent frame.
 *
 * Single-frame misreads (a blurred frame, a reflection read as letters) rarely repeat,
 * while real text does, allowing for small OCR differences between frames (fuzzy match).
 * Pure Kotlin; unit-tested.
 *
 * Costs one frame of latency (~0.4 s) when the content changes.
 */
class TextStabilizer(
    /** Frames considered, including the current one. */
    private val window: Int = 3,
    /** Frames (including the current one) a line must appear in. */
    private val minVotes: Int = 2,
    /** Max normalized edit distance for two readings to count as the same line. */
    private val maxDistance: Double = 0.34,
) {
    private val history = ArrayDeque<List<String>>()

    /**
     * Returns the confirmed lines of [lines], in their current order, or null when none is
     * confirmed yet; the caller then keeps showing what it showed before.
     */
    fun update(lines: List<String>): List<String>? {
        val normalized = lines.map(TextSimilarity::normalize)
        val confirmed = lines.filterIndexed { i, _ -> votes(normalized[i]) + 1 >= minVotes }
        history.addLast(normalized)
        while (history.size > window - 1) history.removeFirst()
        return confirmed.ifEmpty { null }
    }

    fun reset() = history.clear()

    private fun votes(line: String): Int = history.count { frame -> frame.any { similar(line, it) } }

    private fun similar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        // Cheap length check first: very different lengths cannot be within the distance.
        val longer = maxOf(a.length, b.length)
        if (kotlin.math.abs(a.length - b.length).toDouble() / longer > maxDistance) return false
        return TextSimilarity.normalizedDistance(a, b) <= maxDistance
    }
}
