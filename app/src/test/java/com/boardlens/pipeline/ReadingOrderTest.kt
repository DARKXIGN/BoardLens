package com.boardlens.pipeline

import com.boardlens.camera.MotionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReadingOrderTest {

    private fun line(text: String, l: Int, t: Int, r: Int, b: Int, conf: Float = 0.9f) =
        TextLine(text, BBox(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()), conf)

    private fun block(vararg lines: TextLine) = OcrBlock(BBox.unionOf(lines.mapNotNull { it.box }), lines.toList())

    /** Page 1 of the CS407 Lab 1 handout (coordinates from a 918 x 1188 render). */
    private val labPage = listOf(
        block(line("University of Wisconsin-Madison", 87, 90, 283, 103)),
        block(line("CS407 / Fall 2026", 718, 90, 831, 103)),
        block(line("LAB 01", 87, 152, 140, 163)),
        block(line("Introduction to Android Studio and Git", 87, 190, 796, 228)),
        block(line("RELEASED", 87, 270, 159, 281), line("Sep 10, 2026, 1:00 PM CT", 87, 290, 263, 305)),
        block(
            line("DUE", 360, 270, 388, 281), line("Sep 11, 2026, 2:30 PM CT to", 360, 290, 556, 305),
            line("Gradescope", 360, 310, 446, 325),
        ),
        block(
            line("COLLABORATION", 634, 270, 751, 281), line("Pair programming NOT", 634, 290, 787, 305),
            line("allowed", 634, 310, 684, 325),
        ),
        block(line("1 Overview", 87, 387, 207, 409)),
        block(
            line("In this lab, you are going to set up", 87, 430, 831, 446),
            line("Studio which is the official", 87, 451, 831, 467),
            line("This lab consists of the following three main tasks.", 87, 472, 458, 488),
        ),
        block(line("The first one is to run a basic Hello World application", 110, 510, 826, 527)),
        block(line("The second one consists on setting up VCS", 110, 541, 750, 558)),
        block(line("The third one is to Update your app", 110, 573, 616, 590)),
        block(line("2 Learning Objectives", 87, 692, 317, 715)),
        block(
            line("By completing this lab, a cs407 student will be able to:", 87, 737, 566, 754),
        ),
        block(
            line("1. Successfully install Android Studio", 90, 771, 566, 788),
            line("2. Get familiar with the Android Studio interface,", 90, 802, 500, 819),
            line("3. Successfully run a first simple Android application,", 90, 834, 480, 851),
            line("4. Get familiar with Git and GitLab.", 90, 865, 346, 882),
        ),
        block(line("(c) 2026 Banerjee & Kacem", 87, 1130, 395, 1142)),
        block(line("1 / 10", 790, 1130, 831, 1142)),
    )

    private val labExpected = labPage.flatMap { b -> b.lines.map { it.text } }

    @Test
    fun labHandoutReadsInOrderFromAnyBlockOrder() {
        val rng = Random(7)
        repeat(20) {
            val rec = assembleRecognition(labPage.shuffled(rng), LineFilter(), 918, 1188)
            assertEquals(labExpected, rec.lines.map { it.text })
        }
        // The bug the user saw: COLLABORATION must never follow "...will be able to:".
        val text = assembleRecognition(labPage.shuffled(rng), LineFilter(), 918, 1188).fullText
        assertTrue(text.indexOf("COLLABORATION") < text.indexOf("able to:"))
    }

    @Test
    fun headerColumnsAreReadColumnByColumnEvenAsSeparateBlocks() {
        // ML Kit may split each label and its value into separate blocks.
        val parts = listOf(
            block(line("RELEASED", 87, 270, 159, 281)), block(line("Sep 10", 87, 290, 263, 305)),
            block(line("DUE", 360, 270, 388, 281)), block(line("Sep 11", 360, 290, 556, 305)),
            block(line("COLLABORATION", 634, 270, 751, 281)), block(line("Pair programming NOT", 634, 290, 787, 305)),
        )
        val rec = assembleRecognition(parts.shuffled(Random(1)), LineFilter(), 918, 1188)
        assertEquals(listOf("RELEASED", "Sep 10", "DUE", "Sep 11", "COLLABORATION", "Pair programming NOT"), rec.lines.map { it.text })
    }

    @Test
    fun twoColumnSlideIsNotInterleaved() {
        // Bullets in both columns share the same y gaps: classic rows-first XY-cut would interleave them.
        val slide = listOf(
            block(line("Pros and cons", 50, 20, 900, 80)),
            block(line("L1 fast", 50, 120, 430, 160)), block(line("R1 costly", 520, 120, 900, 160)),
            block(line("L2 simple", 50, 200, 430, 240)), block(line("R2 fragile", 520, 200, 900, 240)),
            block(line("L3 local", 50, 280, 430, 320)), block(line("R3 slow", 520, 280, 900, 320)),
        )
        val rec = assembleRecognition(slide.shuffled(Random(3)), LineFilter(), 960, 540)
        assertEquals(
            listOf("Pros and cons", "L1 fast", "L2 simple", "L3 local", "R1 costly", "R2 fragile", "R3 slow"),
            rec.lines.map { it.text },
        )
    }

    @Test
    fun slightlyOverlappingLinesStillReadTopToBottom() {
        // After rectification lines can overlap a few pixels vertically.
        val lines = listOf(line("second", 0, 17, 300, 37), line("first", 5, 0, 280, 20), line("third", 0, 34, 290, 54))
        val rec = assembleRecognition(listOf(block(*lines.toTypedArray())), LineFilter(), 300, 60)
        assertEquals(listOf("first", "second", "third"), rec.lines.map { it.text })
    }

    @Test
    fun filterDropsSymbolsAndLowConfidenceButKeepsUnreportedConfidence() {
        val f = LineFilter(minConfidence = 0.35f)
        assertEquals(LineFilter.Reason.NoAlphanumeric, f.reject(line("- . |", 0, 0, 10, 10)))
        assertEquals(LineFilter.Reason.LowConfidence, f.reject(line("lIlI", 0, 0, 10, 10, conf = 0.2f)))
        assertEquals(null, f.reject(line("Overview", 0, 0, 10, 10, conf = 0.9f)))
        assertEquals(null, f.reject(line("Overview", 0, 0, 10, 10, conf = 0f))) // 0 = not reported
        val rec = assembleRecognition(
            listOf(block(line("Overview", 0, 0, 100, 20)), block(line("~~", 0, 40, 100, 60))), f, 100, 60,
        )
        assertEquals(listOf("Overview"), rec.lines.map { it.text })
        assertEquals(listOf("~~"), rec.rejected.map { it.text })
    }

    @Test
    fun stabilizerShowsOnlyRepeatedLines() {
        val s = TextStabilizer(window = 3, minVotes = 2)
        assertEquals(null, s.update(listOf("Dynamic Programming", "knapsack")))           // nothing confirmed yet
        assertEquals(listOf("Dynamic Programming", "knapsack"),
            s.update(listOf("Dynamic Programming", "knapsack")))
        // A one-frame garbage line is dropped; a slightly different reading still counts.
        assertEquals(listOf("Dynamic Programmlng", "knapsack"),
            s.update(listOf("Dynamic Programmlng", "xq7 wv", "knapsack")))
        // Moving to garbage-only frames: nothing confirmed, caller keeps the old text.
        assertEquals(null, s.update(listOf("vv3k", "@@ii")))
    }

    @Test
    fun stabilizerFollowsANewSlideAfterOneFrame() {
        val s = TextStabilizer()
        s.update(listOf("Slide one"))
        s.update(listOf("Slide one"))
        assertEquals(null, s.update(listOf("Graph search")))            // new content, first sighting
        assertEquals(listOf("Graph search"), s.update(listOf("Graph search")))
    }

    @Test
    fun motionGateHoldsBrieflyAfterFastRotation() {
        val g = MotionGate(thresholdRadPerSec = 0.35f, holdNanos = 300_000_000L)
        assertFalse(g.isMoving(1_000_000_000L))
        g.onSample(1_000_000_000L, 0.05f, 0.08f, 0.02f)    // tremor
        assertFalse(g.isMoving(1_010_000_000L))
        g.onSample(1_100_000_000L, 0.6f, 0.1f, 0.0f)       // panning
        assertTrue(g.isMoving(1_200_000_000L))
        assertTrue(g.isMoving(1_390_000_000L))
        assertFalse(g.isMoving(1_400_000_001L))
    }
}
