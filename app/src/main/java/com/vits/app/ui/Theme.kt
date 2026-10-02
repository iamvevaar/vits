package com.vits.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object VitsColors {
    val Background = Color(0xFF0E0E10)
    val Surface = Color(0xFF1A1A1F)
    val SurfaceHigh = Color(0xFF26262D)
    val Accent = Color(0xFFFF9933)   // saffron
    val AccentDim = Color(0x33FF9933)
    val Text = Color(0xFFF2F2F5)
    val TextDim = Color(0xFF9A9AA5)
    val Grid = Color(0xFF3A3A44)
}

@Composable
fun VitsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VitsColors.Accent,
            onPrimary = Color.Black,
            background = VitsColors.Background,
            surface = VitsColors.Surface,
            surfaceVariant = VitsColors.SurfaceHigh,
            onSurface = VitsColors.Text,
            onSurfaceVariant = VitsColors.TextDim,
        ),
        content = content,
    )
}
