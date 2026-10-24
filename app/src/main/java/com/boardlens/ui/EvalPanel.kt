package com.boardlens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Evaluation capture controls (replaces the reading pane while open).
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
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Evaluation capture", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.semantics { contentDescription = "Close evaluation panel" },
                ) { Text("Close") }
            }
            Text(
                "Saves only the board area, never audio. Record with the instructor's permission " +
                    "and keep people out of the board area.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = eval.distanceText,
                onValueChange = onDistanceChange,
                label = { Text("Distance to board (m)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                enabled = !eval.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = eval.note,
                onValueChange = onNoteChange,
                label = { Text("Note (room, row, lighting, slide #)") },
                singleLine = true,
                enabled = !eval.busy,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onCapture,
                    enabled = boardSelected && !eval.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (eval.captureRemaining > 0) "Capturing… ${eval.captureRemaining} left" else "Capture 10 frames")
                }
                OutlinedButton(onClick = onRun, enabled = !eval.busy, modifier = Modifier.weight(1f)) {
                    Text("Run evaluation")
                }
            }
            if (!boardSelected) {
                Text("Select the board first.", style = MaterialTheme.typography.bodySmall)
            }

            if (eval.running && eval.progressTotal > 0) {
                LinearProgressIndicator(
                    progress = { eval.progressDone.toFloat() / eval.progressTotal },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${eval.progressDone} / ${eval.progressTotal}", style = MaterialTheme.typography.bodySmall)
            }

            eval.message?.let {
                Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }

            Text(
                "Sessions: ${eval.sessionCount}" + (eval.lastSession?.let { " · last: $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onDeleteLast, enabled = eval.lastSession != null && !eval.busy) {
                Text("Delete last session")
            }
            if (eval.folder.isNotEmpty()) {
                Text("Saved in: ${eval.folder}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
