package com.boardlens.pipeline

import android.graphics.Bitmap
import android.graphics.Rect

data class PipelineResult(
    val recognition: Recognition,
    /** Image that went into OCR (after rectify + enhance). */
    val enhancedImage: Bitmap,
    /** Region used, in frame pixels. */
    val region: Quad,
    val textRegions: List<Rect>,
    val enhancerId: String,
    /** Wall time per stage in milliseconds, in execution order. */
    val timingsMs: Map<String, Double>,
)

/** Runs stages 2-6 on one frame. Not thread-safe: call every method from the single analysis thread. */
class ReadingPipeline(
    private val gate: FrameGate = AcceptAllGate,
    private val stabilizer: Stabilizer = PassthroughStabilizer,
    private val regionDetector: RegionDetector,
    private val rectifier: Rectifier,
    private val textDetector: TextDetector = NoOpTextDetector,
    enhancer: Enhancer = IdentityEnhancer,
    private val recognizer: TextRecognizer,
) : AutoCloseable {

    private val history = ArrayDeque<Bitmap>()

    /** Swappable at runtime so baseline and learned enhancers can be compared on the same scene. */
    var enhancer: Enhancer = enhancer
        set(value) {
            field = value
            history.clear()
        }

    /** Wall time of the last gate check, also reported for skipped frames. */
    var lastGateMs: Double = 0.0
        private set

    /** Returns null when the frame was skipped by the gate (e.g. motion blur); keep showing the last result. */
    fun process(frame: Frame): PipelineResult? {
        val timer = StageTimer()
        val accepted = timer.measure("gate") { gate.accept(frame) }
        lastGateMs = timer.timings["gate"] ?: 0.0
        if (!accepted) return null

        val stable = timer.measure("stabilize") { stabilizer.stabilize(frame) }
        val region = timer.measure("region") { regionDetector.detect(stable) }
            ?: Quad.fullFrame(stable.bitmap.width, stable.bitmap.height)
        val rectified = timer.measure("rectify") { rectifier.rectify(stable, region) }
        val textRegions = timer.measure("textDetect") { textDetector.detect(rectified) }
        val activeEnhancer = this.enhancer
        val enhanced = timer.measure("enhance") { activeEnhancer.enhance(rectified, history.toList()) }
        val recognition = timer.measure("ocr") { recognizer.recognize(enhanced) }

        if (activeEnhancer.historySize > 0) {
            history.addLast(rectified)
            while (history.size > activeEnhancer.historySize) history.removeFirst()
        }
        return PipelineResult(recognition, enhanced, region, textRegions, activeEnhancer.id, timer.timings)
    }

    /** Call when the region changes; fused history from another region is meaningless. */
    fun resetHistory() {
        history.clear()
        gate.reset()
    }

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
