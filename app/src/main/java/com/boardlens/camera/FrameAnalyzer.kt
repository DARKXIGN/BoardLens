package com.boardlens.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.boardlens.pipeline.Frame

/**
 * CameraX analyzer that turns frames into upright bitmaps and hands them to [onFrame]
 * at most once every [minIntervalMs]. Runs on the analysis executor.
 */
class FrameAnalyzer(
    private val minIntervalMs: Long = 400,
    private val onFrame: (Frame) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile
    var paused = false

    private var lastRunMs = 0L

    override fun analyze(image: ImageProxy) {
        image.use { proxy ->
            val now = SystemClock.elapsedRealtime()
            if (paused || now - lastRunMs < minIntervalMs) return
            lastRunMs = now
            val upright = proxy.toBitmap().rotated(proxy.imageInfo.rotationDegrees)
            onFrame(Frame(upright, proxy.imageInfo.timestamp))
        }
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
    }
}
