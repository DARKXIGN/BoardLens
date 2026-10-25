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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
import com.boardlens.ui.theme.BoardLensType
import com.boardlens.ui.theme.CameraCardColor
import com.boardlens.ui.theme.CameraOverlay
import com.boardlens.ui.theme.OutlineLocked
import com.boardlens.ui.theme.OutlineLost
import com.boardlens.ui.theme.Pretendard
import com.boardlens.ui.theme.TossIcon
import com.boardlens.ui.theme.TossIconImage
import com.boardlens.ui.theme.blColors
import kotlin.math.roundToInt

/** Same 4:3 request for preview and analysis so both streams cover the same field of view. */
private fun fourByThreeSelector(target: Size) = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
    .build()

private const val TEXT_SIZE_MIN = 16
private const val TEXT_SIZE_MAX = 96
private const val TEXT_SIZE_STEP = 4

@Composable
fun ReaderScreen(vm: ReaderViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val selection = ui.selection
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    var cameraExpanded by rememberSaveable { mutableStateOf(false) }

    // System Back undoes the most recent view change, one step at a time, before leaving the app:
    // whatever sits on top closes first. (The board picker registers its own handler below,
    // which takes precedence while it is open.)
    BackHandler(enabled = showLicenses || showOptions || cameraExpanded || ui.eval.panelOpen || settings.showImage) {
        when {
            showLicenses -> showLicenses = false
            showOptions -> showOptions = false
            cameraExpanded -> cameraExpanded = false
            ui.eval.panelOpen -> vm.closeEvalPanel()
            settings.showImage -> vm.updateSettings { it.copy(showImage = false) }
        }
    }
    // Created here, above the layout switch, so rotating or expanding never restarts the camera.
    val controller = rememberCameraController(vm)
    val overlayOpen = selection != null || showOptions || showLicenses

    Box(Modifier.fillMaxSize().background(blColors.background)) {
        // The live screen stays composed under the picker and sheets so the camera keeps running.
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .then(if (overlayOpen) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            val landscape = maxWidth > maxHeight
            // Unfolded foldables and tablets in portrait: all four tiles fit on one row.
            val wide = !landscape && maxWidth >= 600.dp
            val showHeader = !landscape && !cameraExpanded

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (landscape) 12.dp else 16.dp, vertical = 12.dp),
            ) {
                if (showHeader) {
                    Header(ui = ui, onOpenOptions = { showOptions = true })
                    Spacer(Modifier.height(10.dp))
                }
                ReaderLayout(
                    landscape = landscape,
                    cameraExpanded = cameraExpanded,
                    gap = 10.dp,
                    modifier = Modifier.weight(1f),
                    camera = {
                        CameraPane(
                            vm = vm,
                            ui = ui,
                            controller = controller,
                            expanded = cameraExpanded,
                            onToggleExpanded = { cameraExpanded = !cameraExpanded },
                            showStatus = !showHeader,
                            onOpenOptions = if (landscape && !cameraExpanded) ({ showOptions = true }) else null,
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
                            compact = landscape,
                            oneRow = wide,
                        )
                    },
                )
            }
        }

        if (showOptions) {
            OptionsSheet(
                vm = vm,
                ui = ui,
                settings = settings,
                onClose = { showOptions = false },
                onOpenLicenses = { showLicenses = true },
            )
        }

        if (showLicenses) {
            LicensesScreen(onClose = { showLicenses = false })
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

/** "BoardLens", the tracking status and the options button. Portrait only. */
@Composable
private fun Header(ui: ReaderUiState, onOpenOptions: () -> Unit) {
    val c = blColors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "BoardLens",
            style = BoardLensType.title,
            color = c.textPrimary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        StatusPill(ui)
        IconSquareButton(TossIcon.Gear, description = "Display and tools", onClick = onOpenOptions)
    }
}

/** Board status at a glance: none selected, locked on, lost, or paused. */
@Composable
private fun StatusPill(ui: ReaderUiState, onCamera: Boolean = false) {
    val c = blColors
    data class S(val text: String, val bg: Color, val fg: Color, val dot: Color)
    val s = when {
        ui.frozen -> S("Paused", c.fill, c.textPrimary, c.textSecondary)
        ui.region == null -> S("No board selected", c.fill, c.textSecondary, c.textTertiary)
        ui.tracking == TrackingStatus.Lost -> S("Board lost", c.warnBg, c.warnFg, OutlineLost)
        else -> S("Board locked", c.successBg, c.successFg, Color(0xFF12B76A))
    }
    if (onCamera) {
        Pill(s.text, CameraOverlay, Color.White, dot = s.dot)
    } else {
        Pill(s.text, s.bg, s.fg, dot = s.dot)
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
 * Places camera, reading panel and controls for the current orientation, with [gap]
 * between the cards.
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
    gap: Dp,
    modifier: Modifier,
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
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val g = gap.roundToPx()
        val cameraM = measurables.first { it.layoutId == CAMERA_SLOT }
        val panelM = measurables.first { it.layoutId == PANEL_SLOT }
        val controlsM = measurables.first { it.layoutId == CONTROLS_SLOT }

        if (!landscape) {
            val controlsP = controlsM.measure(Constraints(minWidth = w, maxWidth = w, minHeight = 0, maxHeight = h))
            val controlsTop = (h - controlsP.height).coerceAtLeast(0)
            if (cameraExpanded) {
                val cameraH = (controlsTop - g).coerceAtLeast(0)
                val cameraP = cameraM.measure(Constraints.fixed(w, cameraH))
                layout(w, h) {
                    cameraP.place(0, 0)
                    controlsP.place(0, controlsTop)
                }
            } else {
                val available = (controlsTop - 2 * g).coerceAtLeast(0)
                val cameraH = (available * PORTRAIT_CAMERA_SHARE).roundToInt()
                val panelH = available - cameraH
                val cameraP = cameraM.measure(Constraints.fixed(w, cameraH))
                val panelP = if (panelH > 0) panelM.measure(Constraints.fixed(w, panelH)) else null
                layout(w, h) {
                    cameraP.place(0, 0)
                    panelP?.place(0, cameraH + g)
                    controlsP.place(0, controlsTop)
                }
            }
        } else if (cameraExpanded) {
            // Full-screen camera; the button on the camera brings the rest back.
            val cameraP = cameraM.measure(Constraints.fixed(w, h))
            layout(w, h) { cameraP.place(0, 0) }
        } else {
            val cameraW = ((w - g) * LANDSCAPE_CAMERA_SHARE).roundToInt()
            val sideX = cameraW + g
            val sideW = (w - sideX).coerceAtLeast(0)
            val controlsP = controlsM.measure(Constraints(minWidth = sideW, maxWidth = sideW, minHeight = 0, maxHeight = h))
            val panelH = (h - controlsP.height - g).coerceAtLeast(0)
            val cameraP = cameraM.measure(Constraints.fixed(cameraW, h))
            val panelP = if (panelH > 0) panelM.measure(Constraints.fixed(sideW, panelH)) else null
            layout(w, h) {
                cameraP.place(0, 0)
                panelP?.place(sideX, 0)
                controlsP.place(sideX, h - controlsP.height)
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
    /** Status pill on the preview, when the header (which normally shows it) is hidden. */
    showStatus: Boolean,
    /** Options button on the preview (landscape, where there is no header). */
    onOpenOptions: (() -> Unit)?,
    modifier: Modifier,
) {
    var zoom by remember { mutableFloatStateOf(vm.zoomLinear) }

    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(CameraCardColor),
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    // TextureView-based, so the preview respects the card's rounded corners.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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

        if (showStatus) {
            Box(Modifier.align(Alignment.TopStart).padding(12.dp)) { StatusPill(ui, onCamera = true) }
        }

        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onOpenOptions != null) {
                IconSquareButton(TossIcon.Gear, "Display and tools", onOpenOptions, container = CameraOverlay)
            }
            IconSquareButton(
                icon = if (expanded) TossIcon.Return else TossIcon.Magnifier,
                description = if (expanded) "Show text and controls" else "Make the camera view larger",
                onClick = onToggleExpanded,
                container = CameraOverlay,
            )
        }

        // Zoom changes what the frame covers, which would move a selected region off the board.
        val zoomLocked = ui.region != null
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CameraOverlay)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Zoom", style = BoardLensType.label, color = Color.White)
            Slider(
                value = zoom,
                onValueChange = {
                    zoom = it
                    controller.setLinearZoom(it)
                    vm.onZoomChanged(it)
                },
                enabled = !zoomLocked,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    disabledThumbColor = Color.White.copy(alpha = 0.6f),
                    disabledActiveTrackColor = Color.White.copy(alpha = 0.6f),
                    disabledInactiveTrackColor = Color.White.copy(alpha = 0.2f),
                ),
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = if (zoomLocked) "Zoom, locked while a board area is selected" else "Zoom"
                    },
            )
            if (zoomLocked) {
                Text("Locked", style = BoardLensType.label, color = Color.White.copy(alpha = 0.8f))
            }
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
    val color = if (ui.tracking == TrackingStatus.Lost) OutlineLost else OutlineLocked
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
            drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
            pts.forEach { drawCircle(color, radius = 5.dp.toPx(), center = Offset(it.x, it.y)) }
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
    val c = blColors
    val (background, foreground) = if (settings.inverted) {
        c.readingInvertedBg to c.readingInvertedFg
    } else {
        c.card to c.reading
    }
    val muted = foreground.copy(alpha = 0.72f)

    BlCard(modifier, color = background, padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
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
                SecondaryButton(
                    text = "Back to text",
                    icon = TossIcon.Back,
                    onClick = onHideImage,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Board text", style = BoardLensType.label, color = muted, modifier = Modifier.weight(1f))
                    if (ui.moving) {
                        // Toggles often while handheld, so not a live region; the text below stays put.
                        Pill("Hold still to read", c.warnBg, c.warnFg, icon = TossIcon.Hand)
                    } else if (ui.slideChanges > 0) {
                        // Live region: TalkBack announces only this short line, not every OCR refresh.
                        Pill(
                            "New slide (${ui.slideChanges})",
                            c.accentSoft,
                            c.accentSoftText,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    val placeholder = ui.text.isBlank()
                    Text(
                        text = ui.text.ifBlank {
                            if (ui.region == null) "Point the camera at the board, then tap Select board."
                            else "Looking for text…"
                        },
                        color = if (placeholder) muted else foreground,
                        fontFamily = Pretendard,
                        fontWeight = if (placeholder) FontWeight.Medium else FontWeight.SemiBold,
                        fontSize = if (placeholder) 20.sp else settings.fontSizeSp.sp,
                        lineHeight = if (placeholder) 28.sp else (settings.fontSizeSp * 1.3f).sp,
                        letterSpacing = (-0.2).sp,
                    )
                }

                val notice = ui.notice
                if (notice != null) {
                    Spacer(Modifier.height(8.dp))
                    // Announced once by TalkBack; changes only on user-relevant events.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(c.warnBg)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TossIconImage(TossIcon.Warning, size = 20.dp)
                        Text(notice, style = BoardLensType.body, color = c.warnFg)
                    }
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
                    if (status.isNotEmpty()) {
                        Text(status, style = BoardLensType.small, color = muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The four main controls as a bento grid (2×2, or one row of four on wide screens). */
@Composable
private fun Controls(
    vm: ReaderViewModel,
    ui: ReaderUiState,
    settings: DisplaySettings,
    speaking: Boolean,
    /** Narrow side column (landscape): slightly lower tiles. */
    compact: Boolean,
    oneRow: Boolean,
) {
    val tileHeight = if (compact) 76.dp else 84.dp

    @Composable
    fun Read(modifier: Modifier) = ActionTile(
        // While reading, the same tile stops it, in a colour that stands out.
        icon = if (speaking) TossIcon.Stop else TossIcon.Speaker,
        label = if (speaking) "Stop" else "Read aloud",
        description = if (speaking) "Stop reading aloud" else "Read the text aloud",
        style = if (speaking) TileStyle.Danger else TileStyle.Primary,
        onClick = vm::toggleSpeaking,
        height = tileHeight,
        modifier = modifier,
    )

    @Composable
    fun Freeze(modifier: Modifier) = ActionTile(
        icon = if (ui.frozen) TossIcon.Play else TossIcon.Pause,
        label = if (ui.frozen) "Resume" else "Freeze",
        description = if (ui.frozen) "Resume live reading" else "Freeze the current text",
        style = if (ui.frozen) TileStyle.Selected else TileStyle.Normal,
        onClick = vm::toggleFreeze,
        height = tileHeight,
        modifier = modifier,
    )

    @Composable
    fun Board(modifier: Modifier) = ActionTile(
        icon = TossIcon.Ruler,
        label = if (ui.region == null) "Select board" else "Adjust board",
        onClick = vm::startRegionSelection,
        height = tileHeight,
        modifier = modifier,
    )

    @Composable
    fun TextSize(modifier: Modifier) = TextSizeTile(
        size = settings.fontSizeSp,
        onChange = { next -> vm.updateSettings { it.copy(fontSizeSp = next) } },
        height = tileHeight,
        modifier = modifier,
    )

    val gap = 10.dp
    if (oneRow) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Read(Modifier.weight(1f))
            Freeze(Modifier.weight(1f))
            Board(Modifier.weight(1f))
            TextSize(Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Read(Modifier.weight(1f))
                Freeze(Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Board(Modifier.weight(1f))
                TextSize(Modifier.weight(1f))
            }
        }
    }
}

/** Tile with the current text size and two buttons. */
@Composable
private fun TextSizeTile(size: Int, onChange: (Int) -> Unit, height: Dp, modifier: Modifier) {
    val c = blColors
    Column(
        modifier
            .heightIn(min = height)
            .clip(TileShape)
            .background(c.card)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "Text size · $size",
            style = BoardLensType.label,
            color = c.textSecondary,
            modifier = Modifier.semantics { stateDescription = "Text size $size" },
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = "A−",
                description = "Smaller text",
                enabled = size > TEXT_SIZE_MIN,
                onClick = { onChange((size - TEXT_SIZE_STEP).coerceAtLeast(TEXT_SIZE_MIN)) },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = "A+",
                description = "Larger text",
                enabled = size < TEXT_SIZE_MAX,
                onClick = { onChange((size + TEXT_SIZE_STEP).coerceAtMost(TEXT_SIZE_MAX)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Less frequent settings, in a sheet over the screen. Tapping the dimmed area, the close
 * button or system Back closes it.
 */
@Composable
private fun OptionsSheet(
    vm: ReaderViewModel,
    ui: ReaderUiState,
    settings: DisplaySettings,
    onClose: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val c = blColors
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(c.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Close options",
                    onClick = onClose,
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.92f)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(c.background)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 40.dp, height = 5.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.textTertiary.copy(alpha = 0.4f)),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Display and tools",
                    style = BoardLensType.heading,
                    color = c.textPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                IconSquareButton(TossIcon.Close, "Close options", onClose)
            }

            // Text size with a slider: quicker than many taps on A+ for big jumps.
            BlCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Text size", style = BoardLensType.cardTitle, color = c.textPrimary, modifier = Modifier.weight(1f))
                        Text("${settings.fontSizeSp}", style = BoardLensType.cardTitle, color = c.accentSoftText)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("A", style = BoardLensType.label, color = c.textSecondary)
                        Slider(
                            value = settings.fontSizeSp.toFloat(),
                            onValueChange = { v ->
                                val snapped = (v / TEXT_SIZE_STEP).roundToInt() * TEXT_SIZE_STEP
                                vm.updateSettings { it.copy(fontSizeSp = snapped.coerceIn(TEXT_SIZE_MIN, TEXT_SIZE_MAX)) }
                            },
                            valueRange = TEXT_SIZE_MIN.toFloat()..TEXT_SIZE_MAX.toFloat(),
                            steps = (TEXT_SIZE_MAX - TEXT_SIZE_MIN) / TEXT_SIZE_STEP - 1,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "Text size" },
                        )
                        Text("A", style = BoardLensType.heading, color = c.textSecondary)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleTile(
                    icon = TossIcon.Contrast,
                    title = "High contrast",
                    hint = "Yellow on black",
                    checked = settings.highContrast,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(highContrast = on) } },
                    modifier = Modifier.weight(1f),
                )
                ToggleTile(
                    icon = TossIcon.Arrows,
                    title = "Invert colors",
                    hint = "Swap text and background",
                    checked = settings.inverted,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(inverted = on) } },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleTile(
                    icon = TossIcon.Picture,
                    title = "Show image",
                    hint = "Board photo with text boxes",
                    checked = settings.showImage,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(showImage = on) } },
                    modifier = Modifier.weight(1f),
                )
                ToggleTile(
                    icon = TossIcon.Sparkles,
                    title = "Sharpen text",
                    hint = "Upscale before reading",
                    checked = settings.upscale,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(upscale = on) } },
                    modifier = Modifier.weight(1f),
                )
            }
            ToggleTile(
                icon = TossIcon.Loudspeaker,
                title = "Read new slides aloud",
                hint = "Speak automatically when the slide changes",
                checked = settings.speakNewSlides,
                onCheckedChange = { on -> vm.updateSettings { it.copy(speakNewSlides = on) } },
                modifier = Modifier.fillMaxWidth(),
            )

            if (ui.region != null) {
                ListRow(
                    icon = TossIcon.Screen,
                    title = "Read the whole view",
                    trailing = "Drops the board area",
                    onClick = {
                        vm.clearRegion()
                        onClose()
                    },
                )
            }

            SectionLabel("Developer")
            ListRow(
                icon = TossIcon.Chart,
                title = if (ui.eval.panelOpen) "Close evaluation capture" else "Evaluation capture",
                onClick = {
                    vm.toggleEvalPanel()
                    onClose()
                },
            )

            SectionLabel("About")
            ListRow(icon = TossIcon.Page, title = "Licenses", onClick = onOpenLicenses)
        }
    }
}
