package com.boardlens.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.boardlens.pipeline.FitTransform
import com.boardlens.pipeline.Pt
import com.boardlens.ui.theme.BoardLensType
import com.boardlens.ui.theme.CameraCardColor
import com.boardlens.ui.theme.CameraOverlay
import com.boardlens.ui.theme.OutlineLocked
import com.boardlens.ui.theme.TossIcon
import com.boardlens.ui.theme.TossIconImage
import com.boardlens.ui.theme.blColors

private val HandleColor = OutlineLocked
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
    val c = blColors

    @Composable
    fun TopBar() = Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconSquareButton(TossIcon.Back, "Cancel and go back", onCancel)
        Text(
            "Select the board",
            style = BoardLensType.heading,
            color = c.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
    }

    @Composable
    fun Frame(modifier: Modifier) = Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(CameraCardColor),
    ) {
        CornerEditor(selection, onMoveCorner, Modifier.fillMaxSize())
        Pill(
            "Frame paused",
            CameraOverlay,
            Color.White,
            icon = TossIcon.Pause,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
        )
    }

    @Composable
    fun Actions(modifier: Modifier, stackedButtons: Boolean) = BlCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusRow(selection)
            PrimaryButton("Use this area", onConfirm, Modifier.fillMaxWidth())
            if (stackedButtons) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Auto-detect", onAutoDetect, Modifier.weight(1f), icon = TossIcon.Target, stacked = true)
                    SecondaryButton("Reset", onResetCorners, Modifier.weight(1f), icon = TossIcon.Return, stacked = true)
                    SecondaryButton("Whole view", onWholeFrame, Modifier.weight(1f), icon = TossIcon.Screen, stacked = true)
                }
            } else {
                SecondaryButton("Auto-detect", onAutoDetect, Modifier.fillMaxWidth(), icon = TossIcon.Target)
                SecondaryButton("Reset", onResetCorners, Modifier.fillMaxWidth(), icon = TossIcon.Return)
                SecondaryButton("Whole view", onWholeFrame, Modifier.fillMaxWidth(), icon = TossIcon.Screen)
            }
        }
    }

    // Surface blocks touches from reaching the live screen underneath.
    Surface(modifier.fillMaxSize(), color = c.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth > maxHeight) {
                // Landscape: the frozen frame keeps the full height; controls in a side column.
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Frame(Modifier.weight(1f).fillMaxHeight())
                    Column(
                        Modifier
                            .width(300.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TopBar()
                        Actions(Modifier.fillMaxWidth(), stackedButtons = false)
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TopBar()
                    Frame(Modifier.weight(1f).fillMaxWidth())
                    Actions(Modifier.fillMaxWidth(), stackedButtons = true)
                }
            }
        }
    }
}

/** What the picker found, as an icon, a title and one line of help. Announced on change. */
@Composable
private fun StatusRow(selection: RegionSelection) {
    val c = blColors
    val (icon, title, hint) = when {
        selection.invalid -> Triple(TossIcon.Warning, "Check the corners", "They cross, or the area is too small.")
        selection.autoDetect == AutoDetectStatus.Running -> Triple(TossIcon.Target, "Looking for the board…", "This takes a moment.")
        selection.autoDetect == AutoDetectStatus.Found -> Triple(TossIcon.Check, "Board found", "Check the yellow outline, then tap Use this area.")
        selection.autoDetect == AutoDetectStatus.NotFound -> Triple(TossIcon.Warning, "Couldn't find the board", "Drag the four corners onto it.")
        else -> Triple(TossIcon.Ruler, "Drag the corners onto the board", "Or tap Auto-detect.")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TossIconImage(icon, size = 32.dp)
        Column {
            Text(title, style = BoardLensType.cardTitle, color = if (selection.invalid) c.danger else c.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(hint, style = BoardLensType.caption, color = c.textSecondary)
        }
    }
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
