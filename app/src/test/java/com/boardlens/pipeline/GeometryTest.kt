package com.boardlens.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    private val tl = Pt(10f, 20f)
    private val tr = Pt(110f, 25f)
    private val br = Pt(105f, 90f)
    private val bl = Pt(12f, 85f)

    @Test
    fun ordersShuffledCorners() {
        val q = Quad.fromUnordered(listOf(br, tl, bl, tr))
        assertEquals(Quad(tl, tr, br, bl), q)
    }

    @Test
    fun ordersStronglyRotatedBoard() {
        // A diamond: x+y/x-y heuristics break here, the angle sort does not.
        val top = Pt(50f, 0f)
        val right = Pt(100f, 50f)
        val bottom = Pt(50f, 100f)
        val left = Pt(0f, 50f)
        val q = Quad.fromUnordered(listOf(bottom, left, top, right))
        val order = q.points()
        // Must be clockwise and use every point once.
        assertEquals(4, order.toSet().size)
        assertTrue(q.isConvex())
        val i = order.indexOf(top)
        assertEquals(right, order[(i + 1) % 4])
        assertEquals(bottom, order[(i + 2) % 4])
    }

    @Test
    fun convexity() {
        assertTrue(Quad(tl, tr, br, bl).isConvex())
        // Swapping two corners makes a bow-tie.
        assertFalse(Quad(tl, tr, bl, br).isConvex())
        // Degenerate: three collinear points.
        assertFalse(Quad(Pt(0f, 0f), Pt(1f, 0f), Pt(2f, 0f), Pt(0f, 1f)).isConvex())
    }

    @Test
    fun areaOfUnitSquare() {
        assertEquals(1f, Quad.inset(0f).area(), 1e-6f)
        assertEquals(0.49f, Quad.inset(0.15f).area(), 1e-5f)
    }

    @Test
    fun normalizeRoundTrip() {
        val q = Quad(tl, tr, br, bl)
        val back = q.normalize(200, 100).denormalize(200, 100)
        q.points().zip(back.points()).forEach { (a, b) -> assertEquals(0f, a.distanceTo(b), 1e-4f) }
    }

    @Test
    fun fitCenterLetterboxesPortraitView() {
        // 4:3 landscape frame in a 1080 x 1920 portrait view: bars top and bottom.
        val t = FitTransform.fitCenter(1440, 1080, 1080f, 1920f)
        assertEquals(0.75f, t.scale, 1e-6f)
        assertEquals(0f, t.offsetX, 1e-4f)
        assertEquals((1920f - 810f) / 2f, t.offsetY, 1e-4f)

        val center = t.toView(Pt(0.5f, 0.5f))
        assertEquals(540f, center.x, 1e-3f)
        assertEquals(960f, center.y, 1e-3f)
        assertEquals(Pt(0.5f, 0.5f), t.toNormalized(540f, 960f))
        // Taps in the letterbox bar clamp to the image edge.
        assertEquals(0f, t.toNormalized(540f, 10f).y, 0f)
    }
}
