package com.boardlens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boardlens.ui.theme.BoardLensType
import com.boardlens.ui.theme.TossIcon
import com.boardlens.ui.theme.TossIconImage
import com.boardlens.ui.theme.blColors

// Building blocks for the Toss-style layout: white cards on a grey ground, big rounded
// tiles, one accent colour. Buttons are plain clickable boxes with Role.Button so that
// the only icons are Tossface and the only typeface is Pretendard.

val CardShape = RoundedCornerShape(24.dp)
val TileShape = RoundedCornerShape(22.dp)
val ButtonShape = RoundedCornerShape(14.dp)

@Composable
fun BlCard(
    modifier: Modifier = Modifier,
    color: Color = blColors.card,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(CardShape)
            .background(color)
            .padding(padding),
        content = content,
    )
}

enum class TileStyle { Normal, Primary, Danger, Selected }

@Composable
private fun tileColors(style: TileStyle): Pair<Color, Color> {
    val c = blColors
    return when (style) {
        TileStyle.Normal -> c.card to c.textPrimary
        TileStyle.Primary -> c.accent to c.onAccent
        TileStyle.Danger -> c.danger to c.onDanger
        TileStyle.Selected -> c.accentSoft to c.accentSoftText
    }
}

/** Big bento tile: icon on top, label at the bottom. The main controls. */
@Composable
fun ActionTile(
    icon: TossIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TileStyle = TileStyle.Normal,
    description: String = label,
    height: Dp = 84.dp,
) {
    val (bg, fg) = tileColors(style)
    Column(
        modifier
            .height(height)
            .clip(TileShape)
            .background(bg)
            .then(if (style == TileStyle.Selected) Modifier.border(2.dp, blColors.accent, TileShape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        TossIconImage(icon, size = 26.dp)
        Text(label, style = BoardLensType.cardTitle, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Full-width button: the main action of a card or sheet. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: TossIcon? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val c = blColors
    val bg = if (danger) c.danger else c.accent
    val fg = if (danger) c.onDanger else c.onAccent
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .heightIn(min = 54.dp)
            .clip(ButtonShape)
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) TossIconImage(icon, size = 22.dp)
        Text(text, style = BoardLensType.button.copy(fontSize = BoardLensType.cardTitle.fontSize), color = fg, textAlign = TextAlign.Center)
    }
}

/**
 * Grey button for secondary actions. [stacked] puts the icon above the label, for
 * narrow buttons in a row of three.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: TossIcon? = null,
    enabled: Boolean = true,
    stacked: Boolean = false,
    container: Color = blColors.fill,
    description: String? = null,
) {
    val c = blColors
    val base = modifier
        .alpha(if (enabled) 1f else 0.4f)
        .heightIn(min = 48.dp)
        .clip(ButtonShape)
        .background(container)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
    if (stacked) {
        Column(
            base.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            if (icon != null) TossIconImage(icon, size = 22.dp)
            Text(text, style = BoardLensType.label.copy(fontSize = BoardLensType.body.fontSize), color = c.textPrimary, textAlign = TextAlign.Center, maxLines = 2)
        }
    } else {
        Row(
            base.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) TossIconImage(icon, size = 20.dp)
            Text(text, style = BoardLensType.button, color = c.textPrimary, textAlign = TextAlign.Center)
        }
    }
}

/** Square icon-only button (44dp+ touch target). */
@Composable
fun IconSquareButton(
    icon: TossIcon,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = blColors.card,
    size: Dp = 48.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        TossIconImage(icon, size = 24.dp)
    }
}

/** Small rounded label, optionally with a status dot or icon in front. */
@Composable
fun Pill(
    text: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    icon: TossIcon? = null,
) {
    Row(
        modifier
            .defaultMinSize(minHeight = 32.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        if (icon != null) TossIconImage(icon, size = 16.dp)
        Text(text, style = BoardLensType.label, color = foreground, maxLines = 1)
    }
}

/** On/off track drawn with shapes (no icon). */
@Composable
fun SwitchIndicator(checked: Boolean, modifier: Modifier = Modifier) {
    val c = blColors
    Box(
        modifier
            .width(42.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (checked) c.accent else if (c.isDark) Color(0xFF4E5968) else Color(0xFFD1D6DB))
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(if (checked && c.isDark) c.onAccent else Color.White))
    }
}

/** Option tile with an on/off switch: icon and switch on top, title and hint below. */
@Composable
fun ToggleTile(
    icon: TossIcon,
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = blColors
    Column(
        modifier
            .heightIn(min = 104.dp)
            .clip(TileShape)
            .background(if (checked) c.accentSoft else c.card)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TossIconImage(icon, size = 26.dp)
            Spacer(Modifier.weight(1f))
            SwitchIndicator(checked)
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = BoardLensType.cardTitle.copy(fontSize = BoardLensType.body.fontSize), color = c.textPrimary)
        Text(hint, style = BoardLensType.caption, color = c.textSecondary)
    }
}

/** Full-width row that opens something. */
@Composable
fun ListRow(
    icon: TossIcon,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    val c = blColors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(TileShape)
            .background(c.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TossIconImage(icon, size = 24.dp)
        Text(title, style = BoardLensType.cardTitle.copy(fontSize = BoardLensType.body.fontSize), color = c.textPrimary, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = BoardLensType.caption, color = c.textSecondary)
    }
}

/** Grey section label above a group of rows. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = BoardLensType.label, color = blColors.textSecondary, modifier = modifier.padding(start = 4.dp, top = 4.dp))
}
