package com.boardlens.pipeline

import android.graphics.Bitmap
import android.graphics.Rect

data class PipelineResult(
    val recognition: Recognition,
    val enhancedImage: Bitmap,
    val region: Quad,
    val textRegions: List<Rect>,
    /** Wall time per stage in milliseconds, in execution order. */
    val timingsMs: Map<String, Double>,
)

/** Runs stages 2-6 on one frame. Not thread-safe: call from a single analysis thread. */
class ReadingPipeline(
    private val stabilizer: Stabilizer = PassthroughStabilizer,
    private val regionDetector: RegionDetector,
    private val rectifier: Rectifier,
    private val textDetector: TextDetector = NoOpTextDetector,
    private val enhancer: Enhancer = IdentityEnhancer,
    private val recognizer: TextRecognizer,
) : AutoCloseable {

    private val history = ArrayDeque<Bitmap>()

    fun process(frame: Frame): PipelineResult {
        val timer = StageTimer()
        val stable = timer.measure("stabilize") { stabilizer.stabilize(frame) }
        val region = timer.measure("region") { regionDetector.detect(stable) }
            ?: Quad.fullFrame(stable.bitmap.width, stable.bitmap.height)
        val rectified = timer.measure("rectify") { rectifier.rectify(stable, region) }
        val textRegions = timer.measure("textDetect") { textDetector.detect(rectified) }
        val enhanced = timer.measure("enhance") { enhancer.enhance(rectified, history.toList()) }
        val recognition = timer.measure("ocr") { recognizer.recognize(enhanced) }

        if (enhancer.historySize > 0) {
            history.addLast(rectified)
            while (history.size > enhancer.historySize) history.removeFirst()
        }
        return PipelineResult(recognition, enhanced, region, textRegions, timer.timings)
    }

    /** Call when the region changes; fused history from another region is meaningless. */
    fun resetHistory() = history.clear()

    override fun close() = recognizer.close()
}

class StageTimer {
    val timings = LinkedHashMap<String, Double>()

    inline fun <T> measure(stage: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            timings[stage] = (System.nanoTime() - start) / 1e6
        }
    }
}
