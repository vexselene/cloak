package io.cloak.os.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private val SpaceColors = darkColorScheme(
    primary = Color(0xFF7FB2FF),
    onPrimary = Color(0xFF00305F),
    primaryContainer = Color(0xFF1F4B80),
    onPrimaryContainer = Color(0xFFD6E6FF),
    background = Color(0xFF0E1726),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF16233A),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF22324D),
    onSurfaceVariant = Color(0xFFB4C2D8),
    error = Color(0xFFFFB4AB)
)

val SpaceBackground: Brush = Brush.verticalGradient(
    listOf(Color(0xFF16263F), Color(0xFF0B1220))
)

@Composable
fun SpaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SpaceColors, content = content)
}