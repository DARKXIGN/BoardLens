package com.boardlens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.boardlens.ui.theme.BoardLensType
import com.boardlens.ui.theme.TossIcon
import com.boardlens.ui.theme.blColors

/**
 * Evaluation capture controls (replaces the reading card while open).
 * Protocol: docs/evaluation.md. Only the board crop is saved; no audio.
 */
@Composable
fun EvalPanel(
    eval: EvalUiState,
    boardSelected: Boolean,
    onDistanceChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onCapture: () -> Unit,
    onRun: () -> Unit,
    onDeleteLast: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = blColors
    BlCard(modifier, padding = PaddingValues(0.dp)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Evaluation capture",
                    style = BoardLensType.cardTitle,
                    color = c.textPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                IconSquareButton(TossIcon.Close, "Close evaluation panel", onClose, container = c.fill)
            }
            Text(
                "Saves only the board area, never audio. Record with the instructor's permission " +
                    "and keep people out of the board area.",
                style = BoardLensType.caption,
                color = c.textSecondary,
            )

            OutlinedTextField(
                value = eval.distanceText,
                onValueChange = onDistanceChange,
                label = { Text("Distance to board (m)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                enabled = !eval.busy,
                textStyle = BoardLensType.body.copy(color = c.textPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = eval.note,
                onValueChange = onNoteChange,
                label = { Text("Note (room, row, lighting, slide #)") },
                singleLine = true,
                enabled = !eval.busy,
                textStyle = BoardLensType.body.copy(color = c.textPrimary),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    text = if (eval.captureRemaining > 0) "Capturing… ${eval.captureRemaining} left" else "Capture 10 frames",
                    icon = TossIcon.Camera,
                    onClick = onCapture,
                    enabled = boardSelected && !eval.busy,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "Run evaluation",
                    icon = TossIcon.Chart,
                    onClick = onRun,
                    enabled = !eval.busy,
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.CenterVertically),
                )
            }
            if (!boardSelected) {
                Text("Select the board first.", style = BoardLensType.caption, color = c.textSecondary)
            }

            if (eval.running && eval.progressTotal > 0) {
                LinearProgressIndicator(
                    progress = { eval.progressDone.toFloat() / eval.progressTotal },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${eval.progressDone} / ${eval.progressTotal}", style = BoardLensType.caption, color = c.textSecondary)
            }

            eval.message?.let {
                Text(
                    it,
                    style = BoardLensType.body,
                    color = c.textPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            Text(
                "Sessions: ${eval.sessionCount}" + (eval.lastSession?.let { " · last: $it" } ?: ""),
                style = BoardLensType.caption,
                color = c.textSecondary,
            )
            SecondaryButton(
                text = "Delete last session",
                onClick = onDeleteLast,
                enabled = eval.lastSession != null && !eval.busy,
            )
            if (eval.folder.isNotEmpty()) {
                Text("Saved in: ${eval.folder}", style = BoardLensType.caption, color = c.textSecondary)
            }
        }
    }
}
