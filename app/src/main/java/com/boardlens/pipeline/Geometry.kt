package com.boardlens.pipeline

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/*
 * Pure-Kotlin geometry (no android.* types) so it runs in JVM unit tests.
 */

data class Pt(val x: Float, val y: Float) {
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    fun distanceTo(o: Pt): Float = hypot(x - o.x, y - o.y)
}

/** Board corners, always in order top-left, top-right, bottom-right, bottom-left. */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {

    fun points(): List<Pt> = listOf(tl, tr, br, bl)

    fun map(f: (Pt) -> Pt) = Quad(f(tl), f(tr), f(br), f(bl))

    /** Scale normalized (0..1) corners to pixels. */
    fun denormalize(width: Int, height: Int) = map { Pt(it.x * width, it.y * height) }

    fun normalize(width: Int, height: Int) = map { Pt(it.x / width, it.y / height) }

    fun withCorner(index: Int, p: Pt): Quad = when (index) {
        0 -> copy(tl = p)
        1 -> copy(tr = p)
        2 -> copy(br = p)
        3 -> copy(bl = p)
        else -> throw IndexOutOfBoundsException("corner $index")
    }

    /** Shoelace area, in the units of the coordinates. */
    fun area(): Float {
        val p = points()
        var s = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    /** True when the four corners form a convex, non-self-intersecting quadrilateral. */
    fun isConvex(): Boolean {
        val p = points()
        var sign = 0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-9f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    companion object {
        fun fullFrame(width: Int, height: Int) = Quad(
            Pt(0f, 0f), Pt(width.toFloat(), 0f), Pt(width.toFloat(), height.toFloat()), Pt(0f, height.toFloat()),
        )

        /** Normalized rectangle inset by [margin] on each side; the starting shape for manual selection. */
        fun inset(margin: Float = 0.15f) = Quad(
            Pt(margin, margin), Pt(1 - margin, margin), Pt(1 - margin, 1 - margin), Pt(margin, 1 - margin),
        )

        /**
         * Put four arbitrary points into TL, TR, BR, BL order (image coordinates, y down).
         * Sorting by angle around the centroid handles strongly rotated boards, where the
         * classic x+y / x-y trick picks the same point twice.
         */
        fun fromUnordered(points: List<Pt>): Quad {
            require(points.size == 4) { "need 4 points, got ${points.size}" }
            val cx = points.sumOf { it.x.toDouble() } / 4
            val cy = points.sumOf { it.y.toDouble() } / 4
            // Clockwise on screen (y down) = increasing atan2 angle.
            val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }
            // Start at the point closest to the top-left (smallest x + y).
            val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
            val r = List(4) { sorted[(start + it) % 4] }
            return Quad(r[0], r[1], r[2], r[3])
        }
    }
}

/**
 * Where an image of [srcW] x [srcH] lands when drawn "fit center" into a [dstW] x [dstH] box.
 * Used to map taps and overlays between screen pixels and normalized image coordinates.
 */
data class FitTransform(val scale: Float, val offsetX: Float, val offsetY: Float, val srcW: Int, val srcH: Int) {

    val contentWidth get() = srcW * scale
    val contentHeight get() = srcH * scale

    /** Normalized image point (0..1) -> view pixels. */
    fun toView(p: Pt) = Pt(offsetX + p.x * contentWidth, offsetY + p.y * contentHeight)

    /** View pixels -> normalized image point, clamped to the image. */
    fun toNormalized(viewX: Float, viewY: Float) = Pt(
        ((viewX - offsetX) / contentWidth).coerceIn(0f, 1f),
        ((viewY - offsetY) / contentHeight).coerceIn(0f, 1f),
    )

    companion object {
        fun fitCenter(srcW: Int, srcH: Int, dstW: Float, dstH: Float): FitTransform {
            val scale = min(dstW / srcW, dstH / srcH)
            return FitTransform(scale, (dstW - srcW * scale) / 2f, (dstH - srcH * scale) / 2f, srcW, srcH)
        }
    }
}
