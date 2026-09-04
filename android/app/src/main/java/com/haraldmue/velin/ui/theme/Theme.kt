package com.haraldmue.velin.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VelinDarkColors = darkColorScheme(
    primary = Color(0xFF9BB8FF),
    onPrimary = Color(0xFF092E68),
    secondary = Color(0xFFBBC6DC),
    background = Color(0xFF111214),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF1A1C20),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF23262B),
    onSurfaceVariant = Color(0xFFC4C7C5),
    outline = Color(0xFF44474E),
    error = Color(0xFFFFB4AB),
)

@Composable
fun VelinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VelinDarkColors,
        content = content,
    )
}
