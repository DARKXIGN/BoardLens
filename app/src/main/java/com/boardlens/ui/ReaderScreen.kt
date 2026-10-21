package com.boardlens.ui

import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.camera.core.ImageAnalysis
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boardlens.pipeline.FitTransform

/** Same 4:3 request for preview and analysis so both streams cover the same field of view. */
private fun fourByThreeSelector(target: Size) = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
    .build()

@Composable
fun ReaderScreen(vm: ReaderViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val selection = ui.selection

    Box(Modifier.fillMaxSize()) {
        // The live screen stays composed under the picker so the camera (and its zoom) keeps running.
        Column(
            Modifier
                .fillMaxSize()
                .then(if (selection != null) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            CameraPane(vm, ui, Modifier.weight(1f).fillMaxWidth())
            ReadingPane(ui, settings, Modifier.weight(1f).fillMaxWidth())
            Controls(vm, ui, settings)
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

@Composable
private fun CameraPane(vm: ReaderViewModel, ui: ReaderUiState, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var zoom by remember { mutableFloatStateOf(0f) }

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
        onDispose { controller.unbind() }
    }

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        // FIT_CENTER shows the whole frame, so the overlay below lines up with
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
        }

        // Zoom changes what the frame covers, which would move a selected region off the board.
        val zoomLocked = ui.region != null
        Slider(
            value = zoom,
            onValueChange = {
                zoom = it
                controller.setLinearZoom(it)
            },
            enabled = !zoomLocked,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics {
                    contentDescription = if (zoomLocked) "Zoom, locked while a board area is selected" else "Zoom"
                },
        )
    }
}

/** Draws the selected region on the live preview. Approximate: preview and analysis share a 4:3 field of view. */
@Composable
private fun RegionOverlay(ui: ReaderUiState, modifier: Modifier) {
    val region = ui.region ?: return
    val frame = ui.frameSize ?: return
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
            drawPath(path, Color(0xFFFFD400), style = Stroke(width = 2.5.dp.toPx()))
            pts.forEach { drawCircle(Color(0xFFFFD400), radius = 4.dp.toPx(), center = Offset(it.x, it.y)) }
        }
    }
}

@Composable
private fun ReadingPane(ui: ReaderUiState, settings: DisplaySettings, modifier: Modifier) {
    val (background, foreground) = when {
        settings.inverted -> Color.White to Color.Black
        settings.highContrast -> Color.Black to Color(0xFFFFD400)
        else -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
    }
    Box(modifier.background(background).padding(16.dp)) {
        val image = ui.processedImage
        if (settings.showImage && image != null) {
            // What OCR actually sees: rectified (and upscaled) board. Useful for debugging and demos.
            Image(
                bitmap = image,
                contentDescription = ui.text.ifBlank { "Corrected view of the board" },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
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
            val status = ui.error ?: if (total > 0) "%.0f ms/frame · %s".format(total, ui.enhancerId) else ""
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
private fun Controls(vm: ReaderViewModel, ui: ReaderUiState, settings: DisplaySettings) {
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::startRegionSelection, modifier = Modifier.weight(1f)) {
                Text(if (ui.region == null) "Select board" else "Adjust board")
            }
            if (ui.region != null) {
                OutlinedButton(onClick = vm::clearRegion, modifier = Modifier.weight(1f)) { Text("Whole view") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::speak, modifier = Modifier.weight(1f)) { Text("Read aloud") }
            OutlinedButton(onClick = vm::toggleFreeze, modifier = Modifier.weight(1f)) {
                Text(if (ui.frozen) "Resume" else "Freeze")
            }
        }
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
        }
    }
}
