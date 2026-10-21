package com.boardlens.ui

import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ReaderScreen(vm: ReaderViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        CameraPane(vm, Modifier.weight(1f).fillMaxWidth())
        ReadingPane(ui, settings, Modifier.weight(1f).fillMaxWidth())
        Controls(vm, ui, settings)
    }
}

@Composable
private fun CameraPane(vm: ReaderViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var zoom by remember { mutableFloatStateOf(0f) }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            setImageAnalysisOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            // Text from the back row needs pixels; ask for ~2.7 MP analysis frames.
            setImageAnalysisResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1920, 1440),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            setImageAnalysisAnalyzer(vm.analysisExecutor, vm.analyzer)
        }
    }

    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }

    Column(modifier) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    // FIT_CENTER keeps the whole analysis frame visible, which makes
                    // mapping a tap to frame coordinates (MVP step: region select) simple.
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    this.controller = controller
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics { contentDescription = "Camera view of the board" },
        )
        // TODO(MVP week 1): tap four corners on the preview -> vm.setRegion(normalizedQuad).
        Slider(
            value = zoom,
            onValueChange = {
                zoom = it
                controller.setLinearZoom(it)
            },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = "Zoom" },
        )
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
                text = ui.text.ifBlank { "Point the camera at the board." },
                color = foreground,
                fontSize = settings.fontSizeSp.sp,
                lineHeight = (settings.fontSizeSp * 1.3f).sp,
            )
        }
        val total = ui.timingsMs.values.sum()
        val status = ui.error ?: if (total > 0) "%.0f ms/frame".format(total) else ""
        Text(
            status,
            color = foreground.copy(alpha = 0.6f),
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

@Composable
private fun Controls(vm: ReaderViewModel, ui: ReaderUiState, settings: DisplaySettings) {
    Column(Modifier.padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::speak, modifier = Modifier.weight(1f)) { Text("Read aloud") }
            OutlinedButton(onClick = vm::toggleFreeze, modifier = Modifier.weight(1f)) {
                Text(if (ui.frozen) "Resume" else "Freeze")
            }
        }
        Row(
            Modifier.fillMaxWidth(),
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
        }
    }
}
