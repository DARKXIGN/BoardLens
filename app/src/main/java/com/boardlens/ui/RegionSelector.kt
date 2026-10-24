package com.boardlens.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boardlens.pipeline.FitTransform
import com.boardlens.pipeline.Pt

private val HandleColor = Color(0xFFFFD400)
private val CornerNames = listOf("top left", "top right", "bottom right", "bottom left")

/**
 * Full-screen corner picker over a frozen camera frame.
 *
 * Picking on a still image instead of the live preview means hand shake does not
 * move the target while the user drags, and the corners map exactly to analysis
 * frame pixels (no preview/analysis stream mismatch). Auto-detect gives a starting
 * shape, so a screen-reader user can accept it without dragging.
 */
@Composable
fun RegionSelectorScreen(
    selection: RegionSelection,
    onMoveCorner: (Int, Pt) -> Unit,
    onAutoDetect: () -> Unit,
    onResetCorners: () -> Unit,
    onWholeFrame: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    @Composable
    fun Status(modifier: Modifier) = Text(
        text = statusText(selection),
        color = if (selection.invalid) Color(0xFFFF8A80) else Color.White,
        fontSize = 18.sp,
        modifier = modifier
            .padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )

    // Surface blocks touches from reaching the live screen underneath.
    Surface(modifier.fillMaxSize(), color = Color.Black) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth > maxHeight) {
                // Landscape: the frozen frame keeps the full height; controls in a side column.
                Row(Modifier.fillMaxSize()) {
                    CornerEditor(selection, onMoveCorner, Modifier.weight(1f).fillMaxHeight())
                    Column(
                        Modifier
                            .width(240.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Status(Modifier.fillMaxWidth())
                        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("Use this area") }
                        OutlinedButton(onClick = onAutoDetect, modifier = Modifier.fillMaxWidth()) { Text("Auto-detect") }
                        OutlinedButton(onClick = onResetCorners, modifier = Modifier.fillMaxWidth()) { Text("Reset") }
                        OutlinedButton(onClick = onWholeFrame, modifier = Modifier.fillMaxWidth()) { Text("Whole view") }
                        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Status(Modifier.fillMaxWidth())

                    CornerEditor(selection, onMoveCorner, Modifier.weight(1f).fillMaxWidth())

                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onAutoDetect, modifier = Modifier.weight(1f)) { Text("Auto-detect") }
                            OutlinedButton(onClick = onResetCorners, modifier = Modifier.weight(1f)) { Text("Reset") }
                            OutlinedButton(onClick = onWholeFrame, modifier = Modifier.weight(1f)) { Text("Whole view") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                            Button(onClick = onConfirm, modifier = Modifier.weight(2f)) { Text("Use this area") }
                        }
                    }
                }
            }
        }
    }
}

private fun statusText(selection: RegionSelection): String = when {
    selection.invalid -> "Corners cross or the area is too small. Adjust them and try again."
    selection.autoDetect == AutoDetectStatus.Running -> "Looking for the board…"
    selection.autoDetect == AutoDetectStatus.Found -> "Board found. Drag the corners to adjust, then tap Use this area."
    selection.autoDetect == AutoDetectStatus.NotFound -> "Couldn't find the board. Drag the four corners onto it."
    else -> "Drag the four corners onto the board."
}

@Composable
private fun CornerEditor(selection: RegionSelection, onMoveCorner: (Int, Pt) -> Unit, modifier: Modifier) {
    val currentSelection by rememberUpdatedState(selection)
    val currentOnMove by rememberUpdatedState(onMoveCorner)
    var activeCorner by remember { mutableIntStateOf(-1) }
    // Finger-to-corner offset at grab time, so the corner does not jump under the finger.
    var grabOffset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val fit = remember(selection.size, viewW, viewH) {
            FitTransform.fitCenter(selection.size.width, selection.size.height, viewW, viewH)
        }

        Image(
            bitmap = selection.image,
            contentDescription = "Frozen camera view for choosing the board",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        Canvas(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "Board outline. Corners: " + CornerNames.joinToString()
                }
                .pointerInput(fit) {
                    val touchRadius = 48.dp.toPx()
                    detectDragGestures(
                        onDragStart = { down: Offset ->
                            val corners = currentSelection.quad.points().map { fit.toView(it) }
                            val nearest = corners.indices.minBy { corners[it].distanceTo(Pt(down.x, down.y)) }
                            val c = corners[nearest]
                            if (c.distanceTo(Pt(down.x, down.y)) <= touchRadius) {
                                activeCorner = nearest
                                grabOffset = Offset(c.x - down.x, c.y - down.y)
                            } else {
                                activeCorner = -1
                            }
                        },
                        onDragEnd = { -> activeCorner = -1 },
                        onDragCancel = { -> activeCorner = -1 },
                        onDrag = { change: PointerInputChange, _: Offset ->
                            val index = activeCorner
                            if (index >= 0) {
                                change.consume()
                                val p = change.position + grabOffset
                                currentOnMove(index, fit.toNormalized(p.x, p.y))
                            }
                        },
                    )
                },
        ) {
            val pts = selection.quad.points().map { fit.toView(it) }
            val outline = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1..3) lineTo(pts[i].x, pts[i].y)
                close()
            }
            // Dark under-stroke keeps the yellow line visible on white boards.
            drawPath(outline, Color.Black.copy(alpha = 0.7f), style = Stroke(width = 7.dp.toPx()))
            drawPath(outline, HandleColor, style = Stroke(width = 3.dp.toPx()))
            pts.forEachIndexed { i, p ->
                val center = Offset(p.x, p.y)
                val r = if (i == activeCorner) 20.dp.toPx() else 14.dp.toPx()
                drawCircle(Color.Black, radius = r + 3.dp.toPx(), center = center)
                drawCircle(if (i == activeCorner) Color.White else HandleColor, radius = r, center = center)
            }
        }
    }
}
