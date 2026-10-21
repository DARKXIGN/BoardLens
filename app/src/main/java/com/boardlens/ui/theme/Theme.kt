package com.boardlens.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BoardLensColors = darkColorScheme(
    primary = Color(0xFFFFD400),
    onPrimary = Color.Black,
    surface = Color(0xFF121212),
    onSurface = Color.White,
    background = Color.Black,
    onBackground = Color.White,
)

@Composable
fun BoardLensTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BoardLensColors, content = content)
}
