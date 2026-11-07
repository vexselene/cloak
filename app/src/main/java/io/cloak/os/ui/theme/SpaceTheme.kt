package io.cloak.os.ui.theme

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

object ThemeStore {
    private const val PREFS = "theme"
    private const val KEY = "preset"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): ThemePreset = ThemePreset.fromId(prefs(context).getString(KEY, null))

    fun save(context: Context, preset: ThemePreset) {
        prefs(context).edit().putString(KEY, preset.id).apply()
    }
}

@Stable
class ThemeController(val preset: ThemePreset, val choose: (ThemePreset) -> Unit)

val LocalTheme = compositionLocalOf { ThemeController(ThemePreset.DEFAULT) { } }

private fun ThemePreset.toColorScheme() = darkColorScheme(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer),
    onPrimaryContainer = Color(onPrimaryContainer),
    background = Color(background),
    onBackground = Color(onBackground),
    surface = Color(surface),
    onSurface = Color(onSurface),
    surfaceVariant = Color(surfaceVariant),
    onSurfaceVariant = Color(onSurfaceVariant),
    error = Color(0xFFFFB4AB)
)

@Composable
fun spaceBackground(): Brush {
    val preset = LocalTheme.current.preset
    return remember(preset) {
        Brush.verticalGradient(listOf(Color(preset.gradientTop), Color(preset.gradientBottom)))
    }
}

@Composable
fun SpaceTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var preset by remember { mutableStateOf(ThemeStore.load(context)) }
    val controller = remember(preset) {
        ThemeController(preset) {
            ThemeStore.save(context, it)
            preset = it
        }
    }
    CompositionLocalProvider(LocalTheme provides controller) {
        MaterialTheme(colorScheme = preset.toColorScheme(), content = content)
    }
}