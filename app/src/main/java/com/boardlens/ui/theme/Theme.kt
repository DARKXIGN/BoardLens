package com.boardlens.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.boardlens.R

/**
 * The only typeface in the app (UI and board text). Bundled unmodified from the
 * official release; license in assets/licenses/pretendard_ofl.txt.
 */
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

/**
 * App colours. Two palettes: a light one in the style of Toss (grey ground, white cards,
 * one blue accent) and a high-contrast one (black ground, yellow text) for readers who
 * need it. Every text/background pair used is at least 4.5:1.
 */
@Immutable
data class BoardLensColors(
    val isDark: Boolean,
    /** Screen ground behind the cards. */
    val background: Color,
    /** Cards and tiles. */
    val card: Color,
    /** Secondary buttons and tracks placed on a card. */
    val fill: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    /** Background of a selected option; [accentSoftText] goes on it. */
    val accentSoft: Color,
    val accentSoftText: Color,
    val danger: Color,
    val onDanger: Color,
    val successBg: Color,
    val successFg: Color,
    val warnBg: Color,
    val warnFg: Color,
    /** Board text in the reading card. */
    val reading: Color,
    /** Reading card when "Invert colors" is on. */
    val readingInvertedBg: Color,
    val readingInvertedFg: Color,
    val scrim: Color,
)

/** The camera card is dark in both palettes so the preview reads as a photo. */
val CameraCardColor = Color(0xFF1B1F24)
val CameraOverlay = Color(0x8C000000)
/** Board outline on the preview: yellow while locked on, orange while lost. */
val OutlineLocked = Color(0xFFFFD400)
val OutlineLost = Color(0xFFFF9800)

val LightColors = BoardLensColors(
    isDark = false,
    background = Color(0xFFF2F4F6),
    card = Color(0xFFFFFFFF),
    fill = Color(0xFFF2F4F6),
    border = Color(0xFFE5E8EB),
    textPrimary = Color(0xFF191F28),
    textSecondary = Color(0xFF4E5968),
    textTertiary = Color(0xFF5F6B78),
    accent = Color(0xFF2563EB),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFEAF1FF),
    accentSoftText = Color(0xFF1D4ED8),
    danger = Color(0xFFD92D20),
    onDanger = Color(0xFFFFFFFF),
    successBg = Color(0xFFE7F6EE),
    successFg = Color(0xFF0B6B43),
    warnBg = Color(0xFFFFF4E5),
    warnFg = Color(0xFF8A4B00),
    reading = Color(0xFF191F28),
    readingInvertedBg = Color(0xFF191F28),
    readingInvertedFg = Color(0xFFFFFFFF),
    scrim = Color(0x73000000),
)

val HighContrastColors = BoardLensColors(
    isDark = true,
    background = Color(0xFF000000),
    card = Color(0xFF16181C),
    fill = Color(0xFF2A2D33),
    border = Color(0xFF2A2D33),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFC9CED6),
    textTertiary = Color(0xFFB0B8C1),
    accent = Color(0xFFFFD400),
    onAccent = Color(0xFF000000),
    accentSoft = Color(0xFF2E2400),
    accentSoftText = Color(0xFFFFD400),
    danger = Color(0xFFDC2626),
    onDanger = Color(0xFFFFFFFF),
    successBg = Color(0xFF0E2A1C),
    successFg = Color(0xFF6EE7A8),
    warnBg = Color(0xFF2E2400),
    warnFg = Color(0xFFFFD400),
    reading = Color(0xFFFFD400),
    readingInvertedBg = Color(0xFFFFD400),
    readingInvertedFg = Color(0xFF000000),
    scrim = Color(0xB3000000),
)

val LocalBoardLensColors = staticCompositionLocalOf { LightColors }

/**
 * Type scale (sp, so it follows the phone's font-size setting), based on Android's
 * default sizes: screen title 22, sheet title 20, card title 17, body 16, caption 13.
 */
object BoardLensType {
    val title = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp)
    val heading = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp)
    val cardTitle = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)
    val button = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp)
    val label = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val small = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
}

private fun pretendardTypography(): Typography {
    val base = Typography()
    fun TextStyle.p() = copy(fontFamily = Pretendard)
    return Typography(
        displayLarge = base.displayLarge.p(),
        displayMedium = base.displayMedium.p(),
        displaySmall = base.displaySmall.p(),
        headlineLarge = base.headlineLarge.p(),
        headlineMedium = base.headlineMedium.p(),
        headlineSmall = base.headlineSmall.p(),
        titleLarge = BoardLensType.title,
        titleMedium = BoardLensType.cardTitle,
        titleSmall = BoardLensType.button,
        bodyLarge = BoardLensType.body,
        bodyMedium = base.bodyMedium.p().copy(fontSize = 15.sp),
        bodySmall = BoardLensType.caption,
        labelLarge = BoardLensType.button,
        labelMedium = BoardLensType.label,
        labelSmall = BoardLensType.small,
    )
}

private val Type = pretendardTypography()

private fun BoardLensColors.toMaterial() = if (isDark) {
    darkColorScheme(
        primary = accent, onPrimary = onAccent,
        secondary = accent, onSecondary = onAccent,
        background = background, onBackground = textPrimary,
        surface = card, onSurface = textPrimary,
        surfaceVariant = fill, onSurfaceVariant = textSecondary,
        surfaceContainerHighest = fill,
        outline = textTertiary, outlineVariant = border,
        error = danger, onError = onDanger,
    )
} else {
    lightColorScheme(
        primary = accent, onPrimary = onAccent,
        secondary = accent, onSecondary = onAccent,
        background = background, onBackground = textPrimary,
        surface = card, onSurface = textPrimary,
        surfaceVariant = fill, onSurfaceVariant = textSecondary,
        surfaceContainerHighest = fill,
        outline = textTertiary, outlineVariant = border,
        error = danger, onError = onDanger,
    )
}

@Composable
fun BoardLensTheme(highContrast: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (highContrast) HighContrastColors else LightColors
    CompositionLocalProvider(LocalBoardLensColors provides colors) {
        MaterialTheme(colorScheme = colors.toMaterial(), typography = Type, content = content)
    }
}

/** Shorthand for the current palette. */
val blColors: BoardLensColors
    @Composable get() = LocalBoardLensColors.current
