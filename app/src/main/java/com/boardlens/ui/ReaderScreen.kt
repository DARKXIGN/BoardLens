package com.boardlens.ui

import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boardlens.pipeline.FitTransform
import com.boardlens.pipeline.TrackingStatus
import kotlin.math.roundToInt

/** Same 4:3 request for preview and analysis so both streams cover the same field of view. */
private fun fourByThreeSelector(target: Size) = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
    .build()

@Composable
fun ReaderScreen(vm: ReaderViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val selection = ui.selection
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var cameraExpanded by rememberSaveable { mutableStateOf(false) }

    // System Back undoes the most recent view change, one step at a time, before leaving the app.
    // (The board picker registers its own handler below, which takes precedence while it is open.)
    BackHandler(enabled = cameraExpanded || ui.eval.panelOpen || settings.showImage || showOptions) {
        when {
            cameraExpanded -> cameraExpanded = false
            ui.eval.panelOpen -> vm.closeEvalPanel()
            settings.showImage -> vm.updateSettings { it.copy(showImage = false) }
            showOptions -> showOptions = false
        }
    }
    // Created here, above the layout switch, so rotating or expanding never restarts the camera.
    val controller = rememberCameraController(vm)

    Box(Modifier.fillMaxSize()) {
        // The live screen stays composed under the picker so the camera (and its zoom) keeps running.
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .then(if (selection != null) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            val landscape = maxWidth > maxHeight
            ReaderLayout(
                landscape = landscape,
                cameraExpanded = cameraExpanded,
                camera = {
                    CameraPane(
                        vm = vm,
                        ui = ui,
                        controller = controller,
                        expanded = cameraExpanded,
                        onToggleExpanded = { cameraExpanded = !cameraExpanded },
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                panel = {
                    if (ui.eval.panelOpen) {
                        EvalPanel(
                            eval = ui.eval,
                            boardSelected = ui.region != null,
                            onDistanceChange = vm::setEvalDistance,
                            onNoteChange = vm::setEvalNote,
                            onCapture = { vm.startCapture() },
                            onRun = vm::runEvaluation,
                            onDeleteLast = vm::deleteLastSession,
                            onClose = vm::closeEvalPanel,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        ReadingPane(
                            ui = ui,
                            settings = settings,
                            onHideImage = { vm.updateSettings { it.copy(showImage = false) } },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                },
                controls = {
                    Controls(
                        vm = vm,
                        ui = ui,
                        settings = settings,
                        speaking = speaking,
                        showOptions = showOptions,
                        onToggleOptions = { showOptions = !showOptions },
                        compact = landscape,
                    )
                },
            )
        }

        if (selection != null) {
            BackHandler(onBack = vm::cancelSelection)
            RegionSelectorScreen(
                selection = selection,
                onMoveCorner = vm::moveCorner,
                onAutoDetect = vm::autoDetect,
                onResetCorners = vm::resetSelectionCorners,
                onWholeFrame = vm::useWholeFrame,
                onCancel = vm::cancelSelection,
                onConfirm = vm::confirmSelection,
            )
        }
    }
}

private const val CAMERA_SLOT = "camera"
private const val PANEL_SLOT = "panel"
private const val CONTROLS_SLOT = "controls"

/** Share of the space above the controls that the camera gets in portrait. */
private const val PORTRAIT_CAMERA_SHARE = 0.62f
/** Share of the width that the camera gets in landscape. */
private const val LANDSCAPE_CAMERA_SHARE = 0.6f

/**
 * Places camera, reading panel and controls for the current orientation.
 *
 * Portrait: camera on top (most of the height), reading panel below, controls at the bottom.
 * Landscape: camera on the left at full height; reading panel and controls on the right.
 * Expanded: the camera takes everything except, in portrait, the controls.
 *
 * One custom layout with the same three children in every arrangement, so switching
 * orientation only moves them: the camera preview is never torn down and rebuilt.
 */
@Composable
private fun ReaderLayout(
    landscape: Boolean,
    cameraExpanded: Boolean,
    camera: @Composable () -> Unit,
    panel: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box(Modifier.layoutId(CAMERA_SLOT)) { camera() }
            Box(Modifier.layoutId(PANEL_SLOT)) { panel() }
            Box(Modifier.layoutId(CONTROLS_SLOT)) { controls() }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val cameraM = measurables.first { it.layoutId == CAMERA_SLOT }
        val panelM = measurables.first { it.layoutId == PANEL_SLOT }
        val controlsM = measurables.first { it.layoutId == CONTROLS_SLOT }

        if (!landscape) {
            val controlsP = controlsM.measure(Constraints(minWidth = w, maxWidth = w, minHeight = 0, maxHeight = h))
            val available = (h - controlsP.height).coerceAtLeast(0)
            val cameraH = if (cameraExpanded) available else (available * PORTRAIT_CAMERA_SHARE).roundToInt()
            val panelH = available - cameraH
            val cameraP = cameraM.measure(Constraints.fixed(w, cameraH))
            val panelP = if (panelH > 0) panelM.measure(Constraints.fixed(w, panelH)) else null
            layout(w, h) {
                cameraP.place(0, 0)
                panelP?.place(0, cameraH)
                controlsP.place(0, available)
            }
        } else if (cameraExpanded) {
            // Full-screen camera; the Expand button on the camera brings the rest back.
            val cameraP = cameraM.measure(Constraints.fixed(w, h))
            layout(w, h) { cameraP.place(0, 0) }
        } else {
            val cameraW = (w * LANDSCAPE_CAMERA_SHARE).roundToInt()
            val sideW = w - cameraW
            val controlsP = controlsM.measure(Constraints(minWidth = sideW, maxWidth = sideW, minHeight = 0, maxHeight = h))
            val panelH = (h - controlsP.height).coerceAtLeast(0)
            val cameraP = cameraM.measure(Constraints.fixed(cameraW, h))
            val panelP = if (panelH > 0) panelM.measure(Constraints.fixed(sideW, panelH)) else null
            layout(w, h) {
                cameraP.place(0, 0)
                panelP?.place(cameraW, 0)
                controlsP.place(cameraW, panelH)
            }
        }
    }
}

/** Camera controller bound to the screen's lifecycle, plus gyroscope start/stop and zoom restore. */
@Composable
private fun rememberCameraController(vm: ReaderViewModel): LifecycleCameraController {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            setImageAnalysisOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            // Text from the back row needs pixels; ask for ~2.7 MP analysis frames.
            setImageAnalysisResolutionSelector(fourByThreeSelector(Size(1920, 1440)))
            setPreviewResolutionSelector(fourByThreeSelector(Size(1440, 1080)))
            setImageAnalysisAnalyzer(vm.analysisExecutor, vm.analyzer)
        }
    }

    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)

        // Gyroscope only while the screen is in front (adding the observer replays ON_RESUME).
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.startSensors()
                Lifecycle.Event.ON_PAUSE -> {
                    vm.stopSensors()
                    vm.stopSpeaking()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        // If the activity was recreated (e.g. theme change), put the zoom back once the camera
        // is open: a selected board region is only valid at the zoom it was drawn at.
        val savedZoom = vm.zoomLinear
        var zoomRestored = savedZoom <= 0f
        val zoomObserver = Observer<ZoomState> {
            if (!zoomRestored) {
                zoomRestored = true
                controller.setLinearZoom(savedZoom)
            }
        }
        controller.zoomState.observe(lifecycleOwner, zoomObserver)

        onDispose {
            controller.zoomState.removeObserver(zoomObserver)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            vm.stopSensors()
            controller.unbind()
        }
    }
    return controller
}

@Composable
private fun CameraPane(
    vm: ReaderViewModel,
    ui: ReaderUiState,
    controller: LifecycleCameraController,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier,
) {
    var zoom by remember { mutableFloatStateOf(vm.zoomLinear) }
    val overlayButtonColors = ButtonDefaults.buttonColors(
        containerColor = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
    )

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    // FIT_CENTER shows the whole frame, so the overlay lines up with
                    // the analysis frame using a single letterbox transform.
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    this.controller = controller
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Camera view of the board" },
        )
        RegionOverlay(ui, Modifier.fillMaxSize())

        Button(
            onClick = onToggleExpanded,
            colors = overlayButtonColors,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .semantics {
                    contentDescription = if (expanded) "Show text and controls" else "Make the camera view larger"
                },
        ) { Text(if (expanded) "Shrink" else "Expand") }

        // Zoom changes what the frame covers, which would move a selected region off the board.
        val zoomLocked = ui.region != null
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 16.dp),
        ) {
            Slider(
                value = zoom,
                onValueChange = {
                    zoom = it
                    controller.setLinearZoom(it)
                    vm.onZoomChanged(it)
                },
                enabled = !zoomLocked,
                modifier = Modifier.semantics {
                    contentDescription = if (zoomLocked) "Zoom, locked while a board area is selected" else "Zoom"
                },
            )
        }
    }
}

/**
 * Draws where the tracker sees the board on the live preview (approximate: preview and
 * analysis share a 4:3 field of view). Yellow = locked on, orange = lost, holding position.
 */
@Composable
private fun RegionOverlay(ui: ReaderUiState, modifier: Modifier) {
    val region = ui.trackedRegion ?: ui.region ?: return
    val frame = ui.frameSize ?: return
    val color = if (ui.tracking == TrackingStatus.Lost) Color(0xFFFF9800) else Color(0xFFFFD400)
    BoxWithConstraints(modifier) {
        val fit = FitTransform.fitCenter(frame.width, frame.height, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        Canvas(Modifier.fillMaxSize()) {
            val pts = region.points().map { fit.toView(it) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1..3) lineTo(pts[i].x, pts[i].y)
                close()
            }
            drawPath(path, Color.Black.copy(alpha = 0.6f), style = Stroke(width = 6.dp.toPx()))
            drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
            pts.forEach { drawCircle(color, radius = 4.dp.toPx(), center = Offset(it.x, it.y)) }
        }
    }
}

@Composable
private fun ReadingPane(
    ui: ReaderUiState,
    settings: DisplaySettings,
    onHideImage: () -> Unit,
    modifier: Modifier,
) {
    val (background, foreground) = when {
        settings.inverted -> Color.White to Color.Black
        settings.highContrast -> Color.Black to Color(0xFFFFD400)
        else -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
    }
    Box(modifier.background(background).padding(16.dp)) {
        val image = ui.processedImage
        if (settings.showImage && image != null) {
            // What OCR actually sees: rectified (and upscaled) board. Useful for debugging and demos.
            Box(Modifier.fillMaxSize()) {
                Image(
                    bitmap = image,
                    contentDescription = ui.text.ifBlank { "Corrected view of the board" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                ui.recognition?.let { OcrDebugOverlay(it, Modifier.fillMaxSize()) }
                OutlinedButton(
                    onClick = onHideImage,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) { Text("Back to text") }
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (ui.slideChanges > 0) {
                    // Live region: TalkBack announces only this short line, not every OCR refresh.
                    Text(
                        "New slide (${ui.slideChanges})",
                        color = foreground,
                        fontSize = 16.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Text(
                    text = ui.text.ifBlank {
                        if (ui.region == null) "Point the camera at the board, then tap Select board."
                        else "Looking for text…"
                    },
                    color = foreground,
                    fontSize = settings.fontSizeSp.sp,
                    lineHeight = (settings.fontSizeSp * 1.3f).sp,
                )
            }
        }

        if (ui.moving) {
            // Toggles often while handheld, so not a live region; the text above stays put meanwhile.
            Text(
                "Hold still to read",
                color = foreground,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(background.copy(alpha = 0.85f)),
            )
        }

        val notice = ui.notice
        if (notice != null) {
            // Announced once by TalkBack; changes only on user-relevant events.
            Text(
                notice,
                color = foreground,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(background.copy(alpha = 0.85f))
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            // Debug status refreshes every frame, so it is deliberately not a live region.
            val total = ui.timingsMs.values.sum()
            val status = ui.error ?: if (total > 0) {
                "%.0f ms/frame · %s · %s · blur-skipped %d · motion-skipped %d".format(
                    total, ui.enhancerId, ui.tracking.name.lowercase(), ui.skippedFrames, ui.motionSkipped,
                )
            } else {
                ""
            }
            Text(
                status,
                color = foreground.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(background.copy(alpha = 0.8f)),
            )
        }
    }
}

@Composable
private fun Controls(
    vm: ReaderViewModel,
    ui: ReaderUiState,
    settings: DisplaySettings,
    speaking: Boolean,
    showOptions: Boolean,
    onToggleOptions: () -> Unit,
    /** Narrow side column (landscape): main buttons on two rows instead of one. */
    compact: Boolean,
) {
    val tight = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

    @Composable
    fun SelectButton(modifier: Modifier) = Button(onClick = vm::startRegionSelection, contentPadding = tight, modifier = modifier) {
        Text(if (ui.region == null) "Select board" else "Adjust board", textAlign = TextAlign.Center)
    }

    @Composable
    fun ReadButton(modifier: Modifier) = Button(
        onClick = vm::toggleSpeaking,
        contentPadding = tight,
        // While reading, the same button stops it, in a colour that stands out.
        colors = if (speaking) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
        modifier = modifier.semantics {
            contentDescription = if (speaking) "Stop reading aloud" else "Read the text aloud"
        },
    ) {
        Text(if (speaking) "Stop" else "Read aloud", textAlign = TextAlign.Center)
    }

    @Composable
    fun FreezeButton(modifier: Modifier) = OutlinedButton(onClick = vm::toggleFreeze, contentPadding = tight, modifier = modifier) {
        Text(if (ui.frozen) "Resume" else "Freeze", textAlign = TextAlign.Center)
    }

    @Composable
    fun OptionsButton() = OutlinedButton(
        onClick = onToggleOptions,
        contentPadding = tight,
        modifier = Modifier.semantics { contentDescription = if (showOptions) "Close options" else "More options" },
    ) { Text(if (showOptions) "Close" else "⋯") }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (compact) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectButton(Modifier.weight(1f))
                OptionsButton()
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReadButton(Modifier.weight(1f))
                FreezeButton(Modifier.weight(1f))
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectButton(Modifier.weight(1f))
                ReadButton(Modifier.weight(1f))
                FreezeButton(Modifier.weight(1f))
                OptionsButton()
            }
        }

        // Less frequent settings stay folded away so the camera gets the space.
        if (showOptions) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { vm.updateSettings { it.copy(fontSizeSp = (it.fontSizeSp - 4).coerceAtLeast(16)) } },
                    modifier = Modifier.semantics { contentDescription = "Smaller text" },
                ) { Text("A−") }
                OutlinedButton(
                    onClick = { vm.updateSettings { it.copy(fontSizeSp = (it.fontSizeSp + 4).coerceAtMost(96)) } },
                    modifier = Modifier.semantics { contentDescription = "Larger text" },
                ) { Text("A+") }
                FilterChip(
                    selected = settings.highContrast,
                    onClick = { vm.updateSettings { it.copy(highContrast = !it.highContrast) } },
                    label = { Text("Contrast") },
                )
                FilterChip(
                    selected = settings.inverted,
                    onClick = { vm.updateSettings { it.copy(inverted = !it.inverted) } },
                    label = { Text("Invert") },
                )
                if (ui.region != null) {
                    OutlinedButton(onClick = vm::clearRegion) { Text("Whole view") }
                }
                FilterChip(
                    selected = settings.showImage,
                    onClick = { vm.updateSettings { it.copy(showImage = !it.showImage) } },
                    label = { Text("Show image") },
                )
                FilterChip(
                    selected = settings.upscale,
                    onClick = { vm.updateSettings { it.copy(upscale = !it.upscale) } },
                    label = { Text("Upscale") },
                )
                FilterChip(
                    selected = ui.eval.panelOpen,
                    onClick = vm::toggleEvalPanel,
                    label = { Text("Eval") },
                )
            }
        }
    }
}
