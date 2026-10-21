package com.boardlens.ui

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import com.boardlens.camera.FrameAnalyzer
import com.boardlens.eval.EvalSession
import com.boardlens.eval.EvalStore
import com.boardlens.eval.Evaluator
import com.boardlens.eval.parseDistanceMeters
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
    val eval: EvalUiState = EvalUiState(),
)

/** Evaluation capture panel (docs/evaluation.md). */
data class EvalUiState(
    val panelOpen: Boolean = false,
    val distanceText: String = "",
    val note: String = "",
    /** Frames still to save in the current capture; 0 = not capturing. */
    val captureRemaining: Int = 0,
    val running: Boolean = false,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val message: String? = null,
    val sessionCount: Int = 0,
    val lastSession: String? = null,
    val folder: String = "",
) {
    val busy get() = captureRemaining > 0 || running
}

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

    private val sharpnessGate: SharpnessGate? = if (openCvReady) SharpnessGate() else null

    private val pipeline = ReadingPipeline(
        gate = sharpnessGate ?: AcceptAllGate,
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

    private val evalStore = EvalStore(
        java.io.File(app.getExternalFilesDir(null) ?: app.filesDir, "eval"),
    )

    /** Session being filled by the frame loop; only touched on the analysis thread. */
    private var captureSession: EvalSession? = null
    private var captureTarget = 0

    @Volatile
    private var zoomLinear = 0f

    init {
        analysisExecutor.execute { refreshEvalSessions() }
    }

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
                maybeCapture(frame, result.region, tracking)
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

    // ---- evaluation capture (docs/evaluation.md) -----------------------------------------------

    fun toggleEvalPanel() = updateEval { it.copy(panelOpen = !it.panelOpen, message = null) }

    fun setEvalDistance(text: String) = updateEval { it.copy(distanceText = text) }

    fun setEvalNote(text: String) = updateEval { it.copy(note = text) }

    fun onZoomChanged(linear: Float) {
        zoomLinear = linear
    }

    /** Saves the next [frames] sharp, tracked frames of the selected board into a new session. */
    fun startCapture(frames: Int = CAPTURE_FRAMES) {
        val state = _ui.value
        if (state.eval.busy) return
        if (state.region == null) {
            updateEval { it.copy(message = "Select the board first.") }
            return
        }
        val distance = parseDistanceMeters(state.eval.distanceText)
        if (distance == null) {
            updateEval { it.copy(message = "Enter the distance to the board in meters, e.g. 6 or 6.5.") }
            return
        }
        if (state.frozen) toggleFreeze() // capture needs live frames
        val note = state.eval.note.trim()
        updateEval { it.copy(captureRemaining = frames, message = "Capturing. Hold the phone the way you normally would.") }
        analysisExecutor.execute {
            runCatching { evalStore.newSession(distance, note) }
                .onSuccess {
                    captureSession = it
                    captureTarget = frames
                }
                .onFailure { e ->
                    updateEval { it.copy(captureRemaining = 0, message = "Could not create a session: ${e.message}") }
                }
        }
    }

    /** Analysis thread: adds the current frame to the active capture, if any. */
    private fun maybeCapture(frame: Frame, regionPx: Quad, tracking: TrackingStatus) {
        val session = captureSession ?: return
        if (_ui.value.region == null) {
            finishCapture("Capture stopped: the board area was cleared.")
            return
        }
        // Lost: position is a guess. Off with a tracker: region set a moment ago, not tracked yet.
        if (tracking == TrackingStatus.Lost || (tracker != null && tracking == TrackingStatus.Off)) return

        runCatching {
            session.save(
                frame,
                regionPx,
                linkedMapOf(
                    "tracking" to tracking.name.lowercase(),
                    "sharpness" to sharpnessGate?.lastSharpness,
                    "zoom_linear" to zoomLinear,
                    "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "android_sdk" to Build.VERSION.SDK_INT,
                ),
            )
        }.onFailure { e ->
            Log.w(TAG, "Saving capture failed", e)
            finishCapture("Saving failed: ${e.message}")
            return
        }
        val remaining = captureTarget - session.saved
        if (remaining <= 0) {
            finishCapture("Saved ${session.saved} frames to ${session.id}.")
        } else {
            updateEval { it.copy(captureRemaining = remaining) }
        }
    }

    /** Analysis thread. */
    private fun finishCapture(message: String) {
        captureSession = null
        captureTarget = 0
        updateEval { it.copy(captureRemaining = 0, message = message) }
        refreshEvalSessions()
    }

    /** Runs every saved session through each enhancer variant and writes preds.jsonl files. */
    fun runEvaluation() {
        if (_ui.value.eval.busy) return
        if (!openCvReady) {
            updateEval { it.copy(message = "OpenCV is unavailable on this device.") }
            return
        }
        analyzer.paused = true
        updateEval { it.copy(running = true, progressDone = 0, progressTotal = 0, message = "Running evaluation…") }
        analysisExecutor.execute {
            val evaluator = Evaluator(
                rectifier = OpenCvRectifier(),
                variants = listOf(IdentityEnhancer, BicubicUpscaleEnhancer()),
                recognizerFactory = { MlKitTextRecognizer() },
            )
            val message = runCatching {
                val sessions = evalStore.sessions()
                if (sessions.isEmpty()) {
                    "No captures yet. Capture some frames first."
                } else {
                    val s = evaluator.run(sessions) { done, total ->
                        updateEval { it.copy(progressDone = done, progressTotal = total) }
                    }
                    val unreadable = if (s.failures > 0) " (${s.failures} unreadable)" else ""
                    "Done: ${s.predictions} predictions for ${s.frames} frames in ${s.sessions} sessions$unreadable. " +
                        "Copy the eval folder to your computer to score it."
                }
            }.getOrElse { e ->
                Log.w(TAG, "Evaluation failed", e)
                "Evaluation failed: ${e.message}"
            }
            updateEval { it.copy(running = false, message = message) }
            val state = _ui.value
            analyzer.paused = state.frozen || state.selection != null
        }
    }

    /** Deletes the most recent session (e.g. a capture with the wrong distance). */
    fun deleteLastSession() {
        val eval = _ui.value.eval
        val last = eval.lastSession ?: return
        if (eval.busy) return
        analysisExecutor.execute {
            val ok = evalStore.delete(last)
            updateEval { it.copy(message = if (ok) "Deleted $last." else "Could not delete $last.") }
            refreshEvalSessions()
        }
    }

    /** Analysis thread (file system). */
    private fun refreshEvalSessions() {
        val sessions = evalStore.sessions()
        updateEval {
            it.copy(
                sessionCount = sessions.size,
                lastSession = sessions.lastOrNull()?.name,
                folder = evalStore.root.absolutePath,
            )
        }
    }

    private inline fun updateEval(crossinline f: (EvalUiState) -> EvalUiState) =
        _ui.update { it.copy(eval = f(it.eval)) }

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
        /** Frames per evaluation capture (~4 s of sharp frames). */
        private const val CAPTURE_FRAMES = 10
        private const val LOST_NOTICE = "Lost the board. Point the camera back at it, or tap Adjust board."
    }
}
