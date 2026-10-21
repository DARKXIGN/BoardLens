package com.boardlens.ui

import android.app.Application
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import com.boardlens.camera.FrameAnalyzer
import com.boardlens.pipeline.AcceptAllGate
import com.boardlens.pipeline.BicubicUpscaleEnhancer
import com.boardlens.pipeline.Frame
import com.boardlens.pipeline.IdentityEnhancer
import com.boardlens.pipeline.ManualRegionDetector
import com.boardlens.pipeline.MlKitTextRecognizer
import com.boardlens.pipeline.OpenCvBoardDetector
import com.boardlens.pipeline.OpenCvRectifier
import com.boardlens.pipeline.Pt
import com.boardlens.pipeline.Quad
import com.boardlens.pipeline.ReadingPipeline
import com.boardlens.pipeline.Rectifier
import com.boardlens.pipeline.SharpnessGate
import com.boardlens.pipeline.SlideChangeDetector
import com.boardlens.pipeline.TrackingRegionDetector
import com.boardlens.pipeline.TrackingStatus
import com.boardlens.tts.Speaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class FrameSize(val width: Int, val height: Int) {
    val isLandscape get() = width >= height
}

enum class AutoDetectStatus { Idle, Running, Found, NotFound }

/** The region picker: a frozen camera frame plus the corners being edited (normalized 0..1). */
data class RegionSelection(
    val image: ImageBitmap,
    val size: FrameSize,
    val quad: Quad,
    val autoDetect: AutoDetectStatus = AutoDetectStatus.Idle,
    val invalid: Boolean = false,
)

data class ReaderUiState(
    val text: String = "",
    val frozen: Boolean = false,
    /** Increments on every confirmed slide change; drives the TalkBack announcement. */
    val slideChanges: Int = 0,
    val timingsMs: Map<String, Double> = emptyMap(),
    val error: String? = null,
    /** One-off guidance for the user (e.g. "select the board again"); cleared by the next region action. */
    val notice: String? = null,
    /** Selected board, normalized to the upright frame; null = whole frame. */
    val region: Quad? = null,
    /** Where the tracker currently sees the board (normalized); follows the phone's motion. */
    val trackedRegion: Quad? = null,
    val tracking: TrackingStatus = TrackingStatus.Off,
    /** Frames dropped by the blur gate since start; shown in the debug status line. */
    val skippedFrames: Int = 0,
    val frameSize: FrameSize? = null,
    /** The image that went into OCR, for the "Image" view. */
    val processedImage: ImageBitmap? = null,
    val enhancerId: String = "",
    val selection: RegionSelection? = null,
)

data class DisplaySettings(
    val fontSizeSp: Int = 36,
    val highContrast: Boolean = true,
    val inverted: Boolean = false,
    val showImage: Boolean = false,
    val upscale: Boolean = true,
    val speakNewSlides: Boolean = false,
)

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(ReaderUiState())
    val ui: StateFlow<ReaderUiState> = _ui.asStateFlow()

    private val _settings = MutableStateFlow(DisplaySettings())
    val settings: StateFlow<DisplaySettings> = _settings.asStateFlow()

    private val openCvReady = OpenCVLoader.initLocal().also {
        if (!it) Log.e(TAG, "OpenCV failed to load; perspective correction, tracking and auto-detect disabled")
    }

    /** Fixed corners; used only when OpenCV is unavailable. */
    private val manualRegion = ManualRegionDetector()
    /** Follows the board while the phone moves. Touched only on the analysis thread. */
    private val tracker: TrackingRegionDetector? = if (openCvReady) TrackingRegionDetector() else null
    private val boardDetector = OpenCvBoardDetector()
    private val slideDetector = SlideChangeDetector()
    private val speaker = Speaker(app)

    // All pipeline and OpenCV work happens on this single thread.
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val bicubic = BicubicUpscaleEnhancer()

    private val pipeline = ReadingPipeline(
        gate = if (openCvReady) SharpnessGate() else AcceptAllGate,
        regionDetector = tracker ?: manualRegion,
        rectifier = if (openCvReady) OpenCvRectifier() else Rectifier { frame, _ -> frame.bitmap },
        enhancer = if (openCvReady) bicubic else IdentityEnhancer,
        recognizer = MlKitTextRecognizer(),
    )

    val analyzer = FrameAnalyzer(onFrame = ::onFrame)

    @Volatile
    private var latestFrame: Frame? = null

    /** Frame the picker is showing; only touched on the analysis thread after creation. */
    @Volatile
    private var selectionFrame: Frame? = null

    /** Orientation the region was drawn in; a rotated frame invalidates it. */
    @Volatile
    private var regionFrameSize: FrameSize? = null

    private var frozenBeforeSelection = false

    // ---- frame loop (analysis thread) ----------------------------------------------------------

    private fun onFrame(frame: Frame) {
        latestFrame = frame
        val size = FrameSize(frame.bitmap.width, frame.bitmap.height)

        val drawnIn = regionFrameSize
        if (drawnIn != null && drawnIn.isLandscape != size.isLandscape) {
            // Device rotated: the stored corners no longer point at the board.
            applyRegion(null)
            _ui.update { it.copy(notice = "Screen rotated. Select the board again.") }
        }

        runCatching { pipeline.process(frame) }
            .onSuccess { result ->
                if (result == null) {
                    // Motion-blurred frame: keep showing the previous reading.
                    Log.d(PERF_TAG, "skipped=blur gate=%.1f".format(pipeline.lastGateMs))
                    _ui.update { it.copy(frameSize = size, skippedFrames = it.skippedFrames + 1) }
                    return@onSuccess
                }
                val text = result.recognition.fullText
                val tracking = tracker?.status ?: TrackingStatus.Off
                val lostFor = tracker?.consecutiveLost ?: 0
                val changed = slideDetector.update(text)
                Log.d(PERF_TAG, "enhancer=${result.enhancerId} tracking=$tracking " +
                    result.timingsMs.entries.joinToString(" ") { (k, v) -> "$k=%.1f".format(v) })
                _ui.update {
                    it.copy(
                        // Keep the last good reading on screen when a frame comes back empty.
                        text = text.ifBlank { it.text },
                        slideChanges = if (changed) it.slideChanges + 1 else it.slideChanges,
                        timingsMs = result.timingsMs,
                        frameSize = size,
                        processedImage = result.enhancedImage.asImageBitmap(),
                        enhancerId = result.enhancerId,
                        trackedRegion = when {
                            it.region == null -> null
                            // Region just set; the tracker picks it up on its next frame.
                            tracker != null && tracking == TrackingStatus.Off -> it.region
                            else -> result.region.normalize(size.width, size.height)
                        },
                        tracking = tracking,
                        notice = when {
                            it.region != null && lostFor >= LOST_NOTICE_FRAMES -> LOST_NOTICE
                            it.notice == LOST_NOTICE -> null
                            else -> it.notice
                        },
                        error = null,
                    )
                }
                if (changed && _settings.value.speakNewSlides) speaker.speak(text)
            }
            .onFailure { e ->
                Log.w(TAG, "Pipeline failed", e)
                _ui.update { it.copy(frameSize = size, error = e.message ?: e.javaClass.simpleName) }
            }
    }

    // ---- region selection (main thread) --------------------------------------------------------

    /** Freeze the latest frame and open the corner picker on it. */
    fun startRegionSelection() {
        val frame = latestFrame
        if (frame == null) {
            _ui.update { it.copy(notice = "Waiting for the camera. Try again in a moment.") }
            return
        }
        frozenBeforeSelection = _ui.value.frozen
        analyzer.paused = true
        selectionFrame = frame

        val size = FrameSize(frame.bitmap.width, frame.bitmap.height)
        // Edit the board where the tracker sees it now, not where it was first selected.
        val current = _ui.value.trackedRegion ?: _ui.value.region
        val existing = current?.takeIf { regionFrameSize?.isLandscape == size.isLandscape }
        _ui.update {
            it.copy(
                notice = null,
                selection = RegionSelection(frame.bitmap.asImageBitmap(), size, existing ?: Quad.inset()),
            )
        }
        // First time: propose corners automatically. Editing an existing region: keep it.
        if (existing == null) autoDetect()
    }

    fun autoDetect() {
        val frame = selectionFrame ?: return
        if (!openCvReady) {
            updateSelection { it.copy(autoDetect = AutoDetectStatus.NotFound) }
            return
        }
        updateSelection { it.copy(autoDetect = AutoDetectStatus.Running) }
        analysisExecutor.execute {
            val found = runCatching { boardDetector.detect(frame) }
                .onFailure { Log.w(TAG, "Board detection failed", it) }
                .getOrNull()
            updateSelection { sel ->
                if (selectionFrame !== frame) return@updateSelection sel  // picker was closed or reopened
                if (found == null) {
                    sel.copy(autoDetect = AutoDetectStatus.NotFound)
                } else {
                    sel.copy(
                        quad = found.normalize(sel.size.width, sel.size.height),
                        autoDetect = AutoDetectStatus.Found,
                        invalid = false,
                    )
                }
            }
        }
    }

    fun moveCorner(index: Int, normalized: Pt) =
        updateSelection { it.copy(quad = it.quad.withCorner(index, normalized), invalid = false) }

    fun resetSelectionCorners() = updateSelection { it.copy(quad = Quad.inset(), invalid = false) }

    fun confirmSelection() {
        val sel = _ui.value.selection ?: return
        // Reject crossed corners or a sliver: the homography would be garbage.
        if (!sel.quad.isConvex() || sel.quad.area() < MIN_REGION_AREA) {
            updateSelection { it.copy(invalid = true) }
            return
        }
        regionFrameSize = sel.size
        applyRegion(sel.quad, reference = selectionFrame)
        closeSelection()
    }

    fun useWholeFrame() {
        applyRegion(null)
        closeSelection()
    }

    fun cancelSelection() = closeSelection()

    /** Back to the whole frame, e.g. before changing zoom. */
    fun clearRegion() = applyRegion(null)

    private fun closeSelection() {
        selectionFrame = null
        _ui.update { it.copy(selection = null) }
        analyzer.paused = frozenBeforeSelection
    }

    /**
     * [reference] is the frame the corners were drawn on; the tracker learns the board's
     * appearance from it. Null quad = whole frame, tracking off.
     */
    private fun applyRegion(quad: Quad?, reference: Frame? = null) {
        if (quad == null) regionFrameSize = null
        manualRegion.normalizedQuad = quad
        _ui.update {
            it.copy(
                region = quad,
                trackedRegion = quad,
                tracking = if (quad != null && tracker != null) TrackingStatus.Anchor else TrackingStatus.Off,
                notice = if (it.notice == LOST_NOTICE) null else it.notice,
                text = "",
                processedImage = null,
            )
        }
        analysisExecutor.execute {
            tracker?.setReference(if (quad != null) reference else null, quad)
            pipeline.resetHistory()
            slideDetector.reset()
        }
    }

    private inline fun updateSelection(crossinline f: (RegionSelection) -> RegionSelection) =
        _ui.update { s -> s.selection?.let { s.copy(selection = f(it)) } ?: s }

    // ---- other controls ------------------------------------------------------------------------

    fun toggleFreeze() {
        val frozen = !_ui.value.frozen
        analyzer.paused = frozen
        _ui.update { it.copy(frozen = frozen) }
    }

    fun speak() = speaker.speak(_ui.value.text)

    fun stopSpeaking() = speaker.stop()

    fun updateSettings(transform: (DisplaySettings) -> DisplaySettings) {
        val before = _settings.value
        _settings.update(transform)
        val after = _settings.value
        if (before.upscale != after.upscale && openCvReady) {
            val next = if (after.upscale) bicubic else IdentityEnhancer
            analysisExecutor.execute { pipeline.enhancer = next }
        }
    }

    override fun onCleared() {
        analysisExecutor.execute {
            pipeline.close()
            tracker?.close()
        }
        analysisExecutor.shutdown()
        speaker.close()
    }

    companion object {
        private const val TAG = "BoardLens"
        /** `adb logcat -s BoardLens/Perf` gives per-stage latency for the benchmark table. */
        const val PERF_TAG = "BoardLens/Perf"
        /** Smallest region accepted, as a fraction of the frame (normalized area). */
        private const val MIN_REGION_AREA = 0.005f
        /** ~2 s at the 400 ms analysis interval. */
        private const val LOST_NOTICE_FRAMES = 5
        private const val LOST_NOTICE = "Lost the board. Point the camera back at it, or tap Adjust board."
    }
}
