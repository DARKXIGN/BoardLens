package com.boardlens.pipeline

import android.graphics.Bitmap
import android.graphics.Rect

/*
 * Stage contracts for the reading pipeline (see docs/design.md).
 *
 * Every stage is an interface so a baseline implementation (OpenCV / ML Kit)
 * can be swapped for a learned model without touching the rest of the app.
 * That swap, measured in CER, is the core experiment of this project.
 */

/** One camera frame, already rotated upright. */
class Frame(val bitmap: Bitmap, val timestampNs: Long)

// Quad and Pt live in Geometry.kt (pure Kotlin, unit-tested on the JVM).

data class TextLine(val text: String, val box: Rect?)

data class Recognition(val lines: List<TextLine>) {
    val fullText: String get() = lines.joinToString("\n") { it.text }
}

/** Stage 2: remove hand shake so the region stays locked between frames. */
fun interface Stabilizer {
    fun stabilize(frame: Frame): Frame
}

/** Stage 3a: find the board / slide. Returning null means "use the whole frame". */
fun interface RegionDetector {
    fun detect(frame: Frame): Quad?
}

/** Stage 3b: warp the region into a fronto-parallel rectangle (homography). */
fun interface Rectifier {
    fun rectify(frame: Frame, region: Quad): Bitmap
}

/** Stage 4: text boxes inside the rectified image. Empty list means "treat it as one block". */
fun interface TextDetector {
    fun detect(image: Bitmap): List<Rect>
}

/**
 * Stage 5: multi-frame fusion + super-resolution. This is the stage the
 * custom model replaces. [history] holds the previous rectified frames,
 * oldest first, up to [historySize].
 */
interface Enhancer {
    /** Short stable name used in logs and evaluation output ("none", "bicubic", "sr_x4_b5", ...). */
    val id: String
    val historySize: Int get() = 0
    fun enhance(current: Bitmap, history: List<Bitmap>): Bitmap
}

/** Stage 6: OCR. */
interface TextRecognizer : AutoCloseable {
    fun recognize(image: Bitmap): Recognition
    override fun close() {}
}
