package com.boardlens.pipeline

/**
 * Decides when the recognized text belongs to a new slide.
 *
 * OCR output flickers frame to frame, so a change only counts once the new
 * text has differed from the current slide for [confirmFrames] frames in a row.
 * Pure Kotlin so it runs in JVM unit tests.
 */
class SlideChangeDetector(
    private val changeThreshold: Double = 0.5,
    private val confirmFrames: Int = 2,
    private val minChars: Int = 8,
) {
    private var current: String? = null
    private var pendingCount = 0

    /** Returns true exactly once per confirmed slide change. */
    fun update(text: String): Boolean {
        val normalized = TextSimilarity.normalize(text)
        if (normalized.length < minChars) return false

        val reference = current
        if (reference == null) {
            current = normalized
            return false
        }
        if (TextSimilarity.normalizedDistance(reference, normalized) < changeThreshold) {
            pendingCount = 0
            return false
        }
        pendingCount++
        if (pendingCount >= confirmFrames) {
            current = normalized
            pendingCount = 0
            return true
        }
        return false
    }

    fun reset() {
        current = null
        pendingCount = 0
    }
}

object TextSimilarity {
    private val whitespace = Regex("\\s+")

    fun normalize(text: String): String = text.lowercase().replace(whitespace, " ").trim()

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    /** Edit distance divided by the longer length, in 0..1. */
    fun normalizedDistance(a: String, b: String): Double {
        val longest = maxOf(a.length, b.length)
        return if (longest == 0) 0.0 else levenshtein(a, b).toDouble() / longest
    }
}
