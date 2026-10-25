package com.boardlens.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boardlens.ui.theme.BoardLensType
import com.boardlens.ui.theme.Pretendard
import com.boardlens.ui.theme.TossIcon
import com.boardlens.ui.theme.blColors
import kotlinx.coroutines.delay
import java.util.Date

/**
 * The board text on a full screen, for reading at length. Shows the snapshot taken when it
 * was opened, so the text holds still while live reading continues behind it; "Refresh"
 * takes a new snapshot. The text can be selected and copied.
 */
@Composable
fun FullTextScreen(
    text: String,
    capturedAtMillis: Long,
    /** Live reading is frozen, so the snapshot is also the latest text. */
    frozen: Boolean,
    /** Live reading has moved on since the snapshot. */
    newerText: Boolean,
    fontSizeSp: Int,
    onFontSizeChange: (Int) -> Unit,
    speaking: Boolean,
    onToggleSpeaking: () -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    val c = blColors
    val context = LocalContext.current
    val time = remember(capturedAtMillis) { DateFormat.getTimeFormat(context).format(Date(capturedAtMillis)) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = c.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Landscape and wide screens: all controls on one row, so the text keeps the height.
            val oneRow = maxWidth >= 600.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconSquareButton(TossIcon.Back, "Close full text", onClose)
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Full text",
                            style = BoardLensType.heading,
                            color = c.textPrimary,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            if (frozen) "Frozen · captured $time" else "Captured $time",
                            style = BoardLensType.caption,
                            color = c.textSecondary,
                        )
                    }
                    if (newerText) {
                        Pill(
                            "Newer text",
                            c.accentSoft,
                            c.accentSoftText,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }

                BlCard(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text,
                            color = c.reading,
                            fontFamily = Pretendard,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = fontSizeSp.sp,
                            lineHeight = (fontSizeSp * 1.3f).sp,
                            letterSpacing = (-0.2).sp,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }

                @Composable
                fun Read(modifier: Modifier) = PrimaryButton(
                    text = if (speaking) "Stop" else "Read aloud",
                    icon = if (speaking) TossIcon.Stop else TossIcon.Speaker,
                    danger = speaking,
                    onClick = onToggleSpeaking,
                    modifier = modifier,
                )

                @Composable
                fun Copy(modifier: Modifier) = SecondaryButton(
                    text = if (copied) "Copied" else "Copy",
                    icon = TossIcon.Clipboard,
                    container = c.card,
                    description = if (copied) "Copied to clipboard" else "Copy all text",
                    onClick = {
                        copyToClipboard(context, text)
                        copied = true
                    },
                    modifier = modifier.heightIn(min = 54.dp),
                )

                @Composable
                fun Smaller(modifier: Modifier) = SecondaryButton(
                    text = "A−",
                    description = "Smaller text",
                    container = c.card,
                    enabled = fontSizeSp > DisplaySettings.FONT_MIN,
                    onClick = { onFontSizeChange(DisplaySettings.clampFontSize(fontSizeSp - DisplaySettings.FONT_STEP)) },
                    modifier = modifier.heightIn(min = 54.dp),
                )

                @Composable
                fun Larger(modifier: Modifier) = SecondaryButton(
                    text = "A+",
                    description = "Larger text",
                    container = c.card,
                    enabled = fontSizeSp < DisplaySettings.FONT_MAX,
                    onClick = { onFontSizeChange(DisplaySettings.clampFontSize(fontSizeSp + DisplaySettings.FONT_STEP)) },
                    modifier = modifier.heightIn(min = 54.dp),
                )

                @Composable
                fun Refresh(modifier: Modifier) = SecondaryButton(
                    text = "Refresh",
                    icon = TossIcon.Arrows,
                    container = c.card,
                    description = "Show the latest text",
                    enabled = newerText,
                    onClick = onRefresh,
                    modifier = modifier.heightIn(min = 54.dp),
                )

                if (oneRow) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Read(Modifier.weight(1.4f))
                        Copy(Modifier.weight(1f))
                        Smaller(Modifier.weight(0.7f))
                        Larger(Modifier.weight(0.7f))
                        Refresh(Modifier.weight(1f))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Read(Modifier.weight(1.4f))
                        Copy(Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Smaller(Modifier.weight(1f))
                        Larger(Modifier.weight(1f))
                        Refresh(Modifier.weight(1.4f))
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    // Android 13+ also shows its own confirmation; the button's "Copied" label covers older versions.
    clipboard.setPrimaryClip(ClipData.newPlainText("Board text", text))
}
