package com.boardlens.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideChangeDetectorTest {

    @Test
    fun levenshteinBasics() {
        assertEquals(0, TextSimilarity.levenshtein("abc", "abc"))
        assertEquals(3, TextSimilarity.levenshtein("", "abc"))
        assertEquals(3, TextSimilarity.levenshtein("kitten", "sitting"))
    }

    @Test
    fun ocrJitterIsNotASlideChange() {
        val d = SlideChangeDetector()
        assertFalse(d.update("Lecture 7: Dynamic Programming"))
        assertFalse(d.update("Lecture 7: Dynamic Programmlng"))
        assertFalse(d.update("Lecture 7 Dynamic Programming"))
    }

    @Test
    fun newSlideNeedsConfirmation() {
        val d = SlideChangeDetector(confirmFrames = 2)
        d.update("Lecture 7: Dynamic Programming")
        assertFalse(d.update("Knapsack recurrence: OPT(i, w)"))
        assertTrue(d.update("Knapsack recurrence: OPT(i, w)"))
        assertFalse(d.update("Knapsack recurrence: OPT(i, w)"))
    }

    @Test
    fun shortTextIsIgnored() {
        val d = SlideChangeDetector()
        d.update("Lecture 7: Dynamic Programming")
        assertFalse(d.update("ok"))
        assertFalse(d.update("ok"))
    }
}
