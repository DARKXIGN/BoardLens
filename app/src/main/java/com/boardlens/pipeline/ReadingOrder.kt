package com.boardlens.pipeline

import kotlin.math.max
import kotlin.math.min

/*
 * Reading order and line filtering for OCR output. Pure Kotlin, unit-tested on the JVM.
 *
 * ML Kit orders words within a line, but not lines or blocks relative to each other.
 * Concatenating blocks in the order returned scrambles multi-column layouts (e.g. a
 * "RELEASED | DUE | COLLABORATION" header row ends up in the middle of the body).
 */

/** Axis-aligned box in image pixels (named BBox to avoid clashing with Compose's Box). */
data class BBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top

    fun union(o: BBox) = BBox(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

    companion object {
        fun unionOf(boxes: Collection<BBox>): BBox = boxes.reduce { a, b -> a.union(b) }
    }
}

object ReadingOrder {

    /**
     * Sorts items the way a person reads a page, using recursive XY-cut:
     * - If the region splits into *tall* columns (each spans most of the region's height),
     *   read each column top to bottom, left column first (two-column slides).
     * - Otherwise split into rows at horizontal gaps and read rows top to bottom; each row
     *   is then split into its own columns (a "RELEASED | DUE | COLLABORATION" header).
     * Checking column height first avoids the classic XY-cut failure of interleaving two
     * columns whose paragraph gaps happen to line up.
     */
    fun <T> sort(items: List<T>, boxOf: (T) -> BBox): List<T> = cut(items, boxOf, depth = 0)

    private fun <T> cut(items: List<T>, boxOf: (T) -> BBox, depth: Int): List<T> {
        if (items.size <= 1 || depth > 40) return topThenLeft(items, boxOf)
        val region = BBox.unionOf(items.map(boxOf))

        val columns = split(items, boxOf, alongY = false)
        if (columns.size > 1 && columns.all { BBox.unionOf(it.map(boxOf)).height >= TALL_COLUMN * region.height }) {
            return columns.flatMap { cut(it, boxOf, depth + 1) }
        }
        val rows = split(items, boxOf, alongY = true)
        if (rows.size > 1) {
            return mergeAlignedRows(rows, boxOf).flatMap { band ->
                if (band.size == 1) {
                    cut(band[0], boxOf, depth + 1)
                } else {
                    // Consecutive multi-column rows sharing a gutter form one multi-column
                    // section: read it column by column, not row by row.
                    split(band.flatten(), boxOf, alongY = false).flatMap { cut(it, boxOf, depth + 1) }
                }
            }
        }
        if (columns.size > 1) return columns.flatMap { cut(it, boxOf, depth + 1) }
        return topThenLeft(items, boxOf)
    }

    /**
     * Groups consecutive rows into bands when each has more than one column and they
     * still have a common empty vertical gutter when taken together. Bullets of a
     * two-column slide land in separate rows but share the gutter; a full-width title does not.
     */
    private fun <T> mergeAlignedRows(rows: List<List<T>>, boxOf: (T) -> BBox): List<List<List<T>>> {
        val bands = mutableListOf(mutableListOf(rows[0]))
        for (row in rows.drop(1)) {
            val band = bands.last()
            val rowIsMultiColumn = split(row, boxOf, alongY = false).size > 1
            val bandIsMultiColumn = split(band.flatten(), boxOf, alongY = false).size > 1
            val sharesGutter = split(band.flatten() + row, boxOf, alongY = false).size > 1
            if (rowIsMultiColumn && bandIsMultiColumn && sharesGutter) band.add(row) else bands.add(mutableListOf(row))
        }
        return bands
    }

    /**
     * Groups items separated by empty gaps along one axis, in increasing order.
     * Rows use boxes shrunk by 15% of their height so slightly overlapping lines
     * (skew after rectification, descenders) still separate.
     */
    private fun <T> split(items: List<T>, boxOf: (T) -> BBox, alongY: Boolean): List<List<T>> {
        data class Span<T>(val start: Float, val end: Float, val item: T)

        val spans = items.map { item ->
            val b = boxOf(item)
            if (alongY) {
                val shrink = ROW_SHRINK * b.height
                Span(b.top + shrink, b.bottom - shrink, item)
            } else {
                Span(b.left, b.right, item)
            }
        }.sortedBy { it.start }

        val groups = mutableListOf(mutableListOf(spans[0].item))
        var end = spans[0].end
        for (s in spans.drop(1)) {
            if (s.start > end) groups.add(mutableListOf(s.item)) else groups.last().add(s.item)
            end = max(end, s.end)
        }
        return groups
    }

    private fun <T> topThenLeft(items: List<T>, boxOf: (T) -> BBox) =
        items.sortedWith(compareBy({ boxOf(it).top }, { boxOf(it).left }))

    private const val TALL_COLUMN = 0.6f
    private const val ROW_SHRINK = 0.15f
}

/** Drops lines that are unlikely to be real text. */
class LineFilter(
    /** ML Kit line confidence in [0, 1]; 0 means "not reported" and is not filtered. */
    private val minConfidence: Float = 0.35f,
) {
    enum class Reason { LowConfidence, NoAlphanumeric }

    fun reject(line: TextLine): Reason? = when {
        line.text.none { it.isLetterOrDigit() } -> Reason.NoAlphanumeric
        line.confidence > 0f && line.confidence < minConfidence -> Reason.LowConfidence
        else -> null
    }
}

/** One OCR block (paragraph) with its lines, as the recognizer returns it. */
data class OcrBlock(val box: BBox, val lines: List<TextLine>)

/** Orders blocks by layout, lines inside each block top to bottom, then filters. */
fun assembleRecognition(blocks: List<OcrBlock>, filter: LineFilter, imageWidth: Int, imageHeight: Int): Recognition {
    val ordered = ReadingOrder.sort(blocks) { it.box }.flatMap { block ->
        ReadingOrder.sort(block.lines) { it.box ?: block.box }
    }
    val kept = mutableListOf<TextLine>()
    val rejected = mutableListOf<TextLine>()
    for (line in ordered) {
        if (filter.reject(line) == null) kept += line else rejected += line
    }
    return Recognition(kept, rejected, imageWidth, imageHeight)
}
