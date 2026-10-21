package com.boardlens.pipeline

import android.graphics.Bitmap
import android.graphics.PointF
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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Baseline implementations used by the MVP. Each one is the "before" in a before/after comparison. */

object PassthroughStabilizer : Stabilizer {
    override fun stabilize(frame: Frame) = frame
}

/**
 * Uses the region the user picked on screen. Corners are stored normalized
 * (0..1) so they survive resolution and rotation changes.
 */
class ManualRegionDetector : RegionDetector {
    @Volatile
    var normalizedQuad: Quad? = null

    override fun detect(frame: Frame): Quad? {
        val q = normalizedQuad ?: return null
        val w = frame.bitmap.width.toFloat()
        val h = frame.bitmap.height.toFloat()
        fun s(p: PointF) = PointF(p.x * w, p.y * h)
        return Quad(s(q.tl), s(q.tr), s(q.br), s(q.bl))
    }
}

/** Perspective correction with OpenCV. Requires OpenCVLoader.initLocal() to have run. */
class OpenCvRectifier(private val maxLongSide: Int = 2048) : Rectifier {

    override fun rectify(frame: Frame, region: Quad): Bitmap {
        val src = frame.bitmap
        if (region == Quad.fullFrame(src.width, src.height)) return src

        // Output size from the longer of each pair of opposite edges.
        var outW = max(dist(region.tl, region.tr), dist(region.bl, region.br))
        var outH = max(dist(region.tl, region.bl), dist(region.tr, region.br))
        val scale = minOf(1.0, maxLongSide / max(outW, outH))
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

    private fun dist(a: PointF, b: PointF) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
}

object NoOpTextDetector : TextDetector {
    override fun detect(image: Bitmap) = emptyList<android.graphics.Rect>()
}

object IdentityEnhancer : Enhancer {
    override fun enhance(current: Bitmap, history: List<Bitmap>) = current
}

/** ML Kit on-device Latin OCR (bundled model, no network). Called from the analysis thread. */
class MlKitTextRecognizer : TextRecognizer {
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun recognize(image: Bitmap): Recognition {
        val result = Tasks.await(client.process(InputImage.fromBitmap(image, 0)))
        val lines = result.textBlocks.flatMap { block ->
            block.lines.map { TextLine(it.text, it.boundingBox) }
        }
        return Recognition(lines)
    }

    override fun close() = client.close()
}
