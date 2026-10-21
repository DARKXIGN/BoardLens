package com.boardlens.eval

import com.boardlens.pipeline.Pt
import com.boardlens.pipeline.Quad
import kotlin.math.ceil
import kotlin.math.floor

/*
 * Pure helpers for evaluation capture (no android.* types; unit-tested on the JVM).
 */

/** Integer pixel rectangle, right/bottom exclusive. */
data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * Bounding box of [quad] (frame pixels) grown by [margin] of its size on each side and
 * clamped to the frame. Only this crop is saved, so people and other surroundings
 * outside the board stay out of the dataset (docs/data-collection.md).
 */
fun cropAround(quad: Quad, frameWidth: Int, frameHeight: Int, margin: Float = 0.08f): CropRect {
    val xs = quad.points().map { it.x }
    val ys = quad.points().map { it.y }
    val w = xs.max() - xs.min()
    val h = ys.max() - ys.min()
    val left = floor(xs.min() - w * margin).toInt().coerceIn(0, frameWidth - 1)
    val top = floor(ys.min() - h * margin).toInt().coerceIn(0, frameHeight - 1)
    val right = ceil(xs.max() + w * margin).toInt().coerceIn(left + 1, frameWidth)
    val bottom = ceil(ys.max() + h * margin).toInt().coerceIn(top + 1, frameHeight)
    return CropRect(left, top, right, bottom)
}

/** [quad] expressed in the coordinates of [crop]. */
fun Quad.relativeTo(crop: CropRect): Quad = map { Pt(it.x - crop.left, it.y - crop.top) }

/** Flattened corners [x0, y0, x1, y1, ...] in TL, TR, BR, BL order, as stored in frames.jsonl. */
fun Quad.toFlatList(): List<Float> = points().flatMap { listOf(it.x, it.y) }

fun quadFromFlatList(v: List<Number>): Quad {
    require(v.size == 8) { "expected 8 numbers, got ${v.size}" }
    val p = v.map { it.toFloat() }
    return Quad(Pt(p[0], p[1]), Pt(p[2], p[3]), Pt(p[4], p[5]), Pt(p[6], p[7]))
}

/**
 * Minimal JSON writer for one flat record per line (JSON Lines). Supports strings,
 * numbers, booleans, null, lists of those, and nested maps. Kept dependency-free so
 * the exact output is unit-tested and matches what the Python tools read.
 */
object JsonLine {
    fun encode(record: Map<String, Any?>): String = StringBuilder().also { write(it, record) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> string(sb, v)
            is Boolean -> sb.append(v)
            is Float -> number(sb, v.toDouble())
            is Double -> number(sb, v)
            is Number -> sb.append(v.toLong())
            is Map<*, *> -> {
                sb.append('{')
                v.entries.forEachIndexed { i, (k, value) ->
                    if (i > 0) sb.append(',')
                    string(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                v.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    write(sb, item)
                }
                sb.append(']')
            }
            else -> string(sb, v.toString())
        }
    }

    private fun number(sb: StringBuilder, d: Double) {
        // JSON has no NaN/Infinity.
        if (d.isNaN() || d.isInfinite()) sb.append("null")
        else if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong())
        else sb.append(d)
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

/**
 * Parses the distance the user typed ("6", "6.5", "6,5" with a comma decimal).
 * Returns null unless it is a plausible classroom distance in meters.
 */
fun parseDistanceMeters(text: String): Double? {
    val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return if (v > 0.0 && v <= 60.0) v else null
}

/** Folder-safe session name, e.g. "20261021-153400_d6.5m". */
fun sessionName(timestamp: String, distanceM: Double): String {
    val d = if (distanceM == Math.rint(distanceM)) distanceM.toLong().toString() else distanceM.toString()
    return "${timestamp}_d${d}m"
}
