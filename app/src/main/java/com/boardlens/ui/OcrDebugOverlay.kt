package com.boardlens.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boardlens.pipeline.BBox
import com.boardlens.pipeline.FitTransform
import com.boardlens.pipeline.Pt
import com.boardlens.pipeline.Recognition
import com.boardlens.ui.theme.Pretendard

private val KeptColor = Color(0xFFFFD400)
private val RejectedColor = Color(0xFFFF5252)

/**
 * Debug view over the "Show image" picture: every recognized line gets a box and its
 * reading-order number (yellow), and filtered-out lines are drawn in red. Makes
 * reading-order and false-text problems visible at a glance.
 * Drawn over an Image shown with ContentScale.Fit in the same bounds.
 */
@Composable
fun OcrDebugOverlay(recognition: Recognition, modifier: Modifier = Modifier) {
    if (recognition.imageWidth <= 0 || recognition.imageHeight <= 0) return
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Pretendard)

    BoxWithConstraints(modifier.clearAndSetSemantics { }) {
        val fit = FitTransform.fitCenter(
            recognition.imageWidth, recognition.imageHeight,
            constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(),
        )
        Canvas(Modifier.fillMaxSize()) {
            fun drawBox(box: BBox, color: Color) {
                val tl = fit.toView(Pt(box.left / recognition.imageWidth, box.top / recognition.imageHeight))
                val br = fit.toView(Pt(box.right / recognition.imageWidth, box.bottom / recognition.imageHeight))
                drawRect(color, Offset(tl.x, tl.y), Size(br.x - tl.x, br.y - tl.y), style = Stroke(width = 1.5.dp.toPx()))
            }

            recognition.rejected.forEach { line -> line.box?.let { drawBox(it, RejectedColor) } }
            recognition.lines.forEachIndexed { i, line ->
                val box = line.box ?: return@forEachIndexed
                drawBox(box, KeptColor)
                val anchor = fit.toView(Pt(box.left / recognition.imageWidth, box.top / recognition.imageHeight))
                val text = measurer.measure("${i + 1}", label)
                val pad = 2.dp.toPx()
                val topLeft = Offset(anchor.x, (anchor.y - text.size.height - pad).coerceAtLeast(0f))
                drawRect(
                    KeptColor,
                    topLeft,
                    Size(text.size.width + 2 * pad, text.size.height + pad),
                )
                drawText(text, topLeft = Offset(topLeft.x + pad, topLeft.y))
            }
        }
    }
}
