package io.cloak.os.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private val SpaceColors = darkColorScheme(
    primary = Color(0xFF8FD4A4),
    onPrimary = Color(0xFF0B2A18),
    primaryContainer = Color(0xFF1E3A2A),
    onPrimaryContainer = Color(0xFFBFEBCB),
    secondary = Color(0xFF9DBBA8),
    onSecondary = Color(0xFF12281B),
    background = Color(0xFF0D1511),
    onBackground = Color(0xFFDCEFE2),
    surface = Color(0xFF152019),
    onSurface = Color(0xFFDCEFE2),
    surfaceVariant = Color(0xFF22332A),
    onSurfaceVariant = Color(0xFF8FA89A),
    outline = Color(0xFF3A5546),
    error = Color(0xFFFFB4AB)
)

val SpaceBackground: Brush = Brush.verticalGradient(
    listOf(Color(0xFF14221A), Color(0xFF0A110D))
)

@Composable
fun SpaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SpaceColors, content = content)
}