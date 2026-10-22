package com.boardlens.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Baseline implementations used by the MVP. Each one is the "before" in a before/after comparison. */

object PassthroughStabilizer : Stabilizer {
    override fun stabilize(frame: Frame) = frame
}

/**
 * Uses the region the user picked. Corners are stored normalized (0..1, upright
 * frame) so they survive resolution changes.
 */
class ManualRegionDetector : RegionDetector {
    @Volatile
    var normalizedQuad: Quad? = null

    override fun detect(frame: Frame): Quad? =
        normalizedQuad?.denormalize(frame.bitmap.width, frame.bitmap.height)
}

/** Perspective correction with OpenCV. Requires OpenCVLoader.initLocal() to have run. */
class OpenCvRectifier(private val maxLongSide: Int = 2048) : Rectifier {

    override fun rectify(frame: Frame, region: Quad): Bitmap {
        val src = frame.bitmap
        if (region == Quad.fullFrame(src.width, src.height)) return src

        // Output size from the longer of each pair of opposite edges, so no
        // source pixels are thrown away. Upscaling is the Enhancer's job.
        var outW = max(region.tl.distanceTo(region.tr), region.bl.distanceTo(region.br)).toDouble()
        var outH = max(region.tl.distanceTo(region.bl), region.tr.distanceTo(region.br)).toDouble()
        val scale = min(1.0, maxLongSide / max(outW, outH))
        outW *= scale
        outH *= scale
        val w = outW.roundToInt().coerceAtLeast(1)
        val h = outH.roundToInt().coerceAtLeast(1)

        val srcPts = MatOfPoint2f(*region.points().map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0), Point(w - 1.0, 0.0), Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0),
        )
        val input = Mat()
        val output = Mat()
        val homography = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        try {
            Utils.bitmapToMat(src, input)
            Imgproc.warpPerspective(input, output, homography, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC)
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(output, result)
            return result
        } finally {
            srcPts.release(); dstPts.release(); input.release(); output.release(); homography.release()
        }
    }
}

object NoOpTextDetector : TextDetector {
    override fun detect(image: Bitmap) = emptyList<Rect>()
}

object IdentityEnhancer : Enhancer {
    override val id = "none"
    override fun enhance(current: Bitmap, history: List<Bitmap>) = current
}

/**
 * The standard super-resolution baseline: bicubic upscaling until the long side
 * reaches [targetLongSide] (at most [maxFactor]x). ML Kit recognizes small glyphs
 * better once they cover more pixels, so the learned model has to beat this,
 * not the raw frame.
 */
class BicubicUpscaleEnhancer(
    private val targetLongSide: Int = 1600,
    private val maxFactor: Double = 3.0,
) : Enhancer {
    override val id = "bicubic"

    override fun enhance(current: Bitmap, history: List<Bitmap>): Bitmap {
        val longSide = max(current.width, current.height)
        val factor = min(targetLongSide.toDouble() / longSide, maxFactor)
        if (factor <= 1.05) return current

        val w = (current.width * factor).roundToInt()
        val h = (current.height * factor).roundToInt()
        val input = Mat()
        val output = Mat()
        try {
            Utils.bitmapToMat(current, input)
            Imgproc.resize(input, output, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(output, result)
            return result
        } finally {
            input.release(); output.release()
        }
    }
}

/**
 * ML Kit on-device Latin OCR (bundled model, no network). Called from the analysis thread.
 * ML Kit does not return blocks in reading order, so they are re-ordered by layout
 * ([ReadingOrder]) and filtered ([LineFilter]) before anything uses the text.
 */
class MlKitTextRecognizer(private val filter: LineFilter = LineFilter()) : TextRecognizer {
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun recognize(image: Bitmap): Recognition {
        val result = Tasks.await(client.process(InputImage.fromBitmap(image, 0)))
        val blocks = result.textBlocks.mapNotNull { block ->
            val lines = block.lines.map { TextLine(it.text, it.boundingBox?.toBBox(), it.confidence) }
            val box = block.boundingBox?.toBBox() ?: lines.mapNotNull { it.box }.takeIf { it.isNotEmpty() }?.let { BBox.unionOf(it) }
            box?.let { OcrBlock(it, lines) }
        }
        return assembleRecognition(blocks, filter, image.width, image.height)
    }

    private fun Rect.toBBox() = BBox(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    override fun close() = client.close()
}
