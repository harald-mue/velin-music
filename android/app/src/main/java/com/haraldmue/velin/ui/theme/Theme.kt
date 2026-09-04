package com.haraldmue.velin.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Graphite = Color(0xFF111214)
private val GraphiteMuted = Color(0xFF1C1D20)
private val GraphiteLine = Color(0xFF2C2D31)

private val VelinDarkColors = darkColorScheme(
    primary = Color(0xFF9BB8FF),
    onPrimary = Color(0xFF0B1F3A),
    primaryContainer = GraphiteMuted,
    onPrimaryContainer = Color(0xFFE8EAEE),
    secondary = Color(0xFFC4C6CD),
    onSecondary = Color(0xFF2B2D32),
    secondaryContainer = GraphiteMuted,
    onSecondaryContainer = Color(0xFFDFE1E6),
    tertiary = Color(0xFFC4C6CD),
    onTertiary = Color(0xFF2B2D32),
    background = Graphite,
    onBackground = Color(0xFFECEDEF),
    surface = Graphite,
    onSurface = Color(0xFFECEDEF),
    surfaceDim = Graphite,
    surfaceBright = Graphite,
    surfaceContainerLowest = Graphite,
    surfaceContainerLow = Graphite,
    surfaceContainer = Graphite,
    surfaceContainerHigh = Graphite,
    surfaceContainerHighest = Graphite,
    surfaceVariant = GraphiteMuted,
    onSurfaceVariant = Color(0xFFB4B7BE),
    surfaceTint = Color.Transparent,
    outline = Color(0xFF3A3D44),
    outlineVariant = GraphiteLine,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val VelinTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
)

private val VelinShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun VelinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VelinDarkColors,
        typography = VelinTypography,
        shapes = VelinShapes,
        content = content,
    )
}
