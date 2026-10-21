package com.boardlens.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.boardlens.camera.FrameAnalyzer
import com.boardlens.pipeline.Frame
import com.boardlens.pipeline.ManualRegionDetector
import com.boardlens.pipeline.MlKitTextRecognizer
import com.boardlens.pipeline.OpenCvRectifier
import com.boardlens.pipeline.Quad
import com.boardlens.pipeline.ReadingPipeline
import com.boardlens.pipeline.Rectifier
import com.boardlens.pipeline.SlideChangeDetector
import com.boardlens.tts.Speaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class ReaderUiState(
    val text: String = "",
    val frozen: Boolean = false,
    /** Increments on every confirmed slide change; drives the TalkBack announcement. */
    val slideChanges: Int = 0,
    val timingsMs: Map<String, Double> = emptyMap(),
    val error: String? = null,
)

data class DisplaySettings(
    val fontSizeSp: Int = 36,
    val highContrast: Boolean = true,
    val inverted: Boolean = false,
    val speakNewSlides: Boolean = false,
)

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(ReaderUiState())
    val ui: StateFlow<ReaderUiState> = _ui.asStateFlow()

    private val _settings = MutableStateFlow(DisplaySettings())
    val settings: StateFlow<DisplaySettings> = _settings.asStateFlow()

    private val regionDetector = ManualRegionDetector()
    private val slideDetector = SlideChangeDetector()
    private val speaker = Speaker(app)

    private val openCvReady = OpenCVLoader.initLocal().also {
        if (!it) Log.e(TAG, "OpenCV failed to load; perspective correction disabled")
    }

    // All pipeline work happens on this single thread.
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val pipeline = ReadingPipeline(
        regionDetector = regionDetector,
        rectifier = if (openCvReady) OpenCvRectifier() else Rectifier { frame, _ -> frame.bitmap },
        recognizer = MlKitTextRecognizer(),
    )

    val analyzer = FrameAnalyzer(onFrame = ::onFrame)

    private fun onFrame(frame: Frame) {
        runCatching { pipeline.process(frame) }
            .onSuccess { result ->
                val text = result.recognition.fullText
                val changed = slideDetector.update(text)
                Log.d(PERF_TAG, result.timingsMs.entries.joinToString(" ") { (k, v) -> "$k=%.1f".format(v) })
                _ui.update {
                    it.copy(
                        // Keep the last good reading on screen when a frame comes back empty.
                        text = text.ifBlank { it.text },
                        slideChanges = if (changed) it.slideChanges + 1 else it.slideChanges,
                        timingsMs = result.timingsMs,
                        error = null,
                    )
                }
                if (changed && _settings.value.speakNewSlides) speaker.speak(text)
            }
            .onFailure { e ->
                Log.w(TAG, "Pipeline failed", e)
                _ui.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
    }

    fun toggleFreeze() {
        val frozen = !_ui.value.frozen
        analyzer.paused = frozen
        _ui.update { it.copy(frozen = frozen) }
    }

    fun speak() = speaker.speak(_ui.value.text)

    fun stopSpeaking() = speaker.stop()

    /** [quad] in normalized (0..1) frame coordinates, or null for the full frame. */
    fun setRegion(quad: Quad?) {
        regionDetector.normalizedQuad = quad
        analysisExecutor.execute {
            pipeline.resetHistory()
            slideDetector.reset()
        }
    }

    fun updateSettings(transform: (DisplaySettings) -> DisplaySettings) = _settings.update(transform)

    override fun onCleared() {
        analysisExecutor.execute { pipeline.close() }
        analysisExecutor.shutdown()
        speaker.close()
    }

    companion object {
        private const val TAG = "BoardLens"
        /** `adb logcat -s BoardLens/Perf` gives per-stage latency for the benchmark table. */
        const val PERF_TAG = "BoardLens/Perf"
    }
}
