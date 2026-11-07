package io.cloak.os.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePresetTest {
    @Test fun idsAreUnique() {
        assertEquals(ThemePreset.entries.size, ThemePreset.entries.map { it.id }.toSet().size)
    }

    @Test fun oceanIsTheDefault() {
        assertEquals(ThemePreset.OCEAN, ThemePreset.DEFAULT)
        assertEquals("ocean", ThemePreset.DEFAULT.id)
    }

    @Test fun unknownOrMissingIdsFallBackToTheDefault() {
        assertEquals(ThemePreset.DEFAULT, ThemePreset.fromId(null))
        assertEquals(ThemePreset.DEFAULT, ThemePreset.fromId(""))
        assertEquals(ThemePreset.DEFAULT, ThemePreset.fromId("neon"))
    }

    @Test fun everyIdFindsItself() {
        ThemePreset.entries.forEach { assertEquals(it, ThemePreset.fromId(it.id)) }
    }

    @Test fun thereAreSeveralChoices() {
        assertTrue(ThemePreset.entries.size >= 4)
    }

    @Test fun allColoursAreOpaque() {
        ThemePreset.entries.forEach { p ->
            listOf(
                p.primary, p.onPrimary, p.primaryContainer, p.onPrimaryContainer,
                p.background, p.onBackground, p.surface, p.onSurface,
                p.surfaceVariant, p.onSurfaceVariant, p.gradientTop, p.gradientBottom
            ).forEach { assertEquals("${p.id} $it", 0xFFL, it ushr 24) }
        }
    }
}