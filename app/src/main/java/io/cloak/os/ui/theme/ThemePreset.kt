package io.cloak.os.ui.theme

import androidx.annotation.StringRes
import io.cloak.os.R

/** Colours as 0xAARRGGBB, so the whole table is plain data. */
enum class ThemePreset(
    val id: String,
    @StringRes val labelRes: Int,
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val background: Long,
    val onBackground: Long,
    val surface: Long,
    val onSurface: Long,
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    val gradientTop: Long,
    val gradientBottom: Long
) {
    OCEAN(
        "ocean", R.string.theme_ocean,
        0xFF7FB2FF, 0xFF00305F, 0xFF1F4B80, 0xFFD6E6FF,
        0xFF0E1726, 0xFFE6EDF7, 0xFF16233A, 0xFFE6EDF7,
        0xFF22324D, 0xFFB4C2D8, 0xFF16263F, 0xFF0B1220
    ),
    AMOLED(
        "amoled", R.string.theme_amoled,
        0xFFE8E8E8, 0xFF000000, 0xFF1A1A1A, 0xFFF0F0F0,
        0xFF000000, 0xFFEDEDED, 0xFF141414, 0xFFEDEDED,
        0xFF262626, 0xFF9A9A9A, 0xFF000000, 0xFF000000
    ),
    FOREST(
        "forest", R.string.theme_forest,
        0xFF8FD4A4, 0xFF0B2A18, 0xFF1E3A2A, 0xFFBFEBCB,
        0xFF0D1511, 0xFFDCEFE2, 0xFF152019, 0xFFDCEFE2,
        0xFF2A3D33, 0xFF8FA89A, 0xFF14221A, 0xFF0A110D
    ),
    DUSK(
        "dusk", R.string.theme_dusk,
        0xFFC3A6FF, 0xFF2E1A5E, 0xFF473178, 0xFFE8DDFF,
        0xFF14111F, 0xFFE9E4F5, 0xFF1D1830, 0xFFE9E4F5,
        0xFF3A3258, 0xFFC6BDDB, 0xFF1E1933, 0xFF100D1A
    ),
    ROSE(
        "rose", R.string.theme_rose,
        0xFFFF9EB8, 0xFF5C0F2A, 0xFF7D2A44, 0xFFFFD9E3,
        0xFF1A1014, 0xFFF5E4EA, 0xFF261820, 0xFFF5E4EA,
        0xFF4A323D, 0xFFD4BCC5, 0xFF2B1A22, 0xFF130C10
    );

    companion object {
        val DEFAULT = OCEAN

        fun fromId(id: String?): ThemePreset = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}