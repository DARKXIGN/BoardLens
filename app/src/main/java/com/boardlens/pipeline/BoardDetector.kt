package com.boardlens.pipeline

import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * Classical board / slide finder used to propose starting corners for the
 * region picker. It looks for the largest convex quadrilateral in two views of
 * the frame: an edge map (board frames, bezels) and Otsu-thresholded masks
 * (bright projected slides, dark chalkboards). The user can always correct it,
 * so it favors "usually close" over "always right".
 *
 * A learned detector can later replace this behind the same [RegionDetector] contract.
 */
class OpenCvBoardDetector(
    private val workLongSide: Int = 640,
    private val minAreaFraction: Double = 0.05,
    private val maxAreaFraction: Double = 0.97,
) : RegionDetector {

    override fun detect(frame: Frame): Quad? {
        val src = frame.bitmap
        val scale = min(1.0, workLongSide.toDouble() / max(src.width, src.height))

        val rgba = Mat()
        val small = Mat()
        val gray = Mat()
        val edges = Mat()
        val mask = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        try {
            Utils.bitmapToMat(src, rgba)
            Imgproc.resize(rgba, small, Size(), scale, scale, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

            val imageArea = gray.rows().toDouble() * gray.cols()
            val candidates = mutableListOf<Quad>()

            Imgproc.Canny(gray, edges, 40.0, 120.0)
            Imgproc.dilate(edges, edges, kernel)
            candidates += quadrilaterals(edges, imageArea)

            for (type in intArrayOf(Imgproc.THRESH_BINARY, Imgproc.THRESH_BINARY_INV)) {
                Imgproc.threshold(gray, mask, 0.0, 255.0, type or Imgproc.THRESH_OTSU)
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
                candidates += quadrilaterals(mask, imageArea)
            }

            val best = candidates.maxByOrNull { it.area() } ?: return null
            val inv = (1.0 / scale).toFloat()
            return best.map { Pt(it.x * inv, it.y * inv) }
        } finally {
            rgba.release(); small.release(); gray.release(); edges.release(); mask.release(); kernel.release()
        }
    }

    private fun quadrilaterals(binary: Mat, imageArea: Double): List<Quad> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()

        val found = mutableListOf<Quad>()
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < minAreaFraction * imageArea || area > maxAreaFraction * imageArea) {
                contour.release()
                continue
            }
            val curve = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(curve, true)
            for (epsilon in doubleArrayOf(0.02, 0.04)) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(curve, approx, epsilon * perimeter, true)
                val pts = approx.toArray()
                approx.release()
                if (pts.size == 4) {
                    val quad = Quad.fromUnordered(pts.map { Pt(it.x.toFloat(), it.y.toFloat()) })
                    if (quad.isConvex()) found += quad
                    break
                }
            }
            curve.release()
            contour.release()
        }
        return found
    }
}
