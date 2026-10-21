package com.boardlens.pipeline

import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/** Downscaled grayscale copy of a frame plus the factor used. Caller releases [gray]. */
private class WorkImage(val gray: Mat, val scale: Double)

private fun toWorkImage(frame: Frame, longSide: Int): WorkImage {
    val bmp = frame.bitmap
    val scale = min(1.0, longSide.toDouble() / max(bmp.width, bmp.height))
    val rgba = Mat()
    val small = Mat()
    val gray = Mat()
    try {
        Utils.bitmapToMat(bmp, rgba)
        Imgproc.resize(rgba, small, Size(), scale, scale, Imgproc.INTER_AREA)
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
    } finally {
        rgba.release(); small.release()
    }
    return WorkImage(gray, scale)
}

/**
 * Keeps the user's board region locked onto the board while the phone moves.
 *
 * Features: ORB on a ~640 px grayscale copy. Matching: ratio test + RANSAC
 * homography from a reference view to the current frame, then the reference
 * quad is mapped through it. Policy (anchor, frame-to-frame fallback,
 * promotion on slide change): [TrackingPolicy].
 *
 * Simulated handheld results (tools/tracking/simulate.py, median corner error
 * as % of board width): steady hand 2.0% vs 5.1% with fixed corners;
 * strong drift 2.3% vs 17% before a slide change.
 *
 * Call [setReference] and [detect] from the same (analysis) thread.
 */
class TrackingRegionDetector(
    private val workLongSide: Int = 640,
    nFeatures: Int = 1000,
    private val ratioTest: Float = 0.8f,
    private val minInliers: Int = 15,
    private val minInlierRatio: Double = 0.4,
    private val margin: Float = 0.25f,
    private val minAreaRatio: Float = 0.6f,
    private val maxAreaRatio: Float = 1.6f,
    promoteAfter: Int = 3,
) : RegionDetector, AutoCloseable {

    /** Features of one frame around a quad (work-image coordinates). */
    private class View(val keypoints: Array<KeyPoint>, val descriptors: Mat, val quad: Quad)

    private val orb: ORB = ORB.create(nFeatures)
    private val matcher: DescriptorMatcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
    private val policy = TrackingPolicy<View>(promoteAfter) { it.descriptors.release() }
    private val noMask = Mat()

    private var referenceArea = 0f

    @Volatile
    var status: TrackingStatus = TrackingStatus.Off
        private set

    @Volatile
    var consecutiveLost: Int = 0
        private set

    /** Start tracking [normalizedQuad] as seen in [frame]; nulls stop tracking. */
    fun setReference(frame: Frame?, normalizedQuad: Quad?) {
        if (frame == null || normalizedQuad == null) {
            policy.reset(null, null)
            status = TrackingStatus.Off
            consecutiveLost = 0
            return
        }
        val work = toWorkImage(frame, workLongSide)
        try {
            val quad = normalizedQuad
                .denormalize(frame.bitmap.width, frame.bitmap.height)
                .map { Pt((it.x * work.scale).toFloat(), (it.y * work.scale).toFloat()) }
            referenceArea = quad.area()
            policy.reset(viewAt(work.gray, quad), quad)
            status = policy.status
            consecutiveLost = 0
        } finally {
            work.gray.release()
        }
    }

    override fun detect(frame: Frame): Quad? {
        if (policy.quad == null) return null
        val work = toWorkImage(frame, workLongSide)
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        try {
            orb.detectAndCompute(work.gray, noMask, keypoints, descriptors)
            val kp = keypoints.toArray()
            val quad = policy.step(
                match = { ref -> match(ref, kp, descriptors) },
                viewAt = { q -> viewAt(work.gray, q) },
            ) ?: return null
            status = policy.status
            consecutiveLost = policy.consecutiveLost
            val inv = (1.0 / work.scale).toFloat()
            return quad.map { Pt(it.x * inv, it.y * inv) }
        } finally {
            keypoints.release(); descriptors.release(); work.gray.release()
        }
    }

    /** ORB features inside the quad enlarged by [margin] (board edges and a bit of wall help). */
    private fun viewAt(gray: Mat, quad: Quad): View {
        val cx = quad.points().map { it.x }.average().toFloat()
        val cy = quad.points().map { it.y }.average().toFloat()
        val grown = quad.map { Pt(cx + (it.x - cx) * (1 + margin), cy + (it.y - cy) * (1 + margin)) }

        val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val poly = MatOfPoint(*grown.points().map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        try {
            Imgproc.fillConvexPoly(mask, poly, Scalar(255.0))
            orb.detectAndCompute(gray, mask, keypoints, descriptors)
            return View(keypoints.toArray(), descriptors, quad)
        } finally {
            mask.release(); poly.release(); keypoints.release()
        }
    }

    /** Quad of [ref] mapped into the current frame, or null if the match is not trustworthy. */
    private fun match(ref: View, kp: Array<KeyPoint>, descriptors: Mat): Quad? {
        if (descriptors.empty() || ref.descriptors.empty() || kp.size < 8) return null

        val knn = ArrayList<MatOfDMatch>()
        matcher.knnMatch(ref.descriptors, descriptors, knn, 2)
        val good = knn.mapNotNull { pair ->
            val m = pair.toArray()
            pair.release()
            if (m.size == 2 && m[0].distance < ratioTest * m[1].distance) m[0] else null
        }
        if (good.size < minInliers) return null

        val src = MatOfPoint2f(*good.map { ref.keypoints[it.queryIdx].pt }.toTypedArray())
        val dst = MatOfPoint2f(*good.map { kp[it.trainIdx].pt }.toTypedArray())
        val inlierMask = Mat()
        val homography = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 4.0, inlierMask)
        val corners = MatOfPoint2f(*ref.quad.points().map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val mapped = MatOfPoint2f()
        try {
            if (homography.empty()) return null
            val inliers = Core.countNonZero(inlierMask)
            if (inliers < minInliers || inliers < minInlierRatio * good.size) return null

            Core.perspectiveTransform(corners, mapped, homography)
            val p = mapped.toArray().map { Pt(it.x.toFloat(), it.y.toFloat()) }
            val quad = Quad(p[0], p[1], p[2], p[3])
            // Same distance and locked zoom: a large size change or a twisted quad means a bad fit.
            val areaRatio = quad.area() / referenceArea
            if (!quad.isConvex() || areaRatio < minAreaRatio || areaRatio > maxAreaRatio) return null
            return quad
        } finally {
            src.release(); dst.release(); inlierMask.release(); homography.release()
            corners.release(); mapped.release()
        }
    }

    override fun close() {
        policy.releaseAll()
        noMask.release()
    }
}

/** Stage-2 gate: drops frames blurred by hand motion before any other work happens. */
class SharpnessGate(
    private val workLongSide: Int = 480,
    private val policy: SharpnessGatePolicy = SharpnessGatePolicy(),
) : FrameGate {

    @Volatile
    var lastSharpness: Double = 0.0
        private set

    override fun accept(frame: Frame): Boolean {
        val work = toWorkImage(frame, workLongSide)
        val lap = Mat()
        val mean = MatOfDouble()
        val std = MatOfDouble()
        try {
            // Variance of the Laplacian: high for crisp edges, drops sharply with motion blur.
            Imgproc.Laplacian(work.gray, lap, CvType.CV_32F)
            Core.meanStdDev(lap, mean, std)
            val sd = std.toArray().firstOrNull() ?: 0.0
            lastSharpness = sd * sd
            return policy.accept(lastSharpness)
        } finally {
            lap.release(); mean.release(); std.release(); work.gray.release()
        }
    }

    override fun reset() = policy.reset()
}
