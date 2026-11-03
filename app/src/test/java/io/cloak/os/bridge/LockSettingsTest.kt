package io.cloak.os.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockSettingsTest {
    @Test fun defaultsAreOff() {
        assertEquals("00", LockSettings().encode())
    }

    @Test fun everyCombinationSurvivesRoundTrip() {
        for (bits in 0 until 4) {
            val s = LockSettings(bits and 1 != 0, bits and 2 != 0)
            assertEquals(s, LockSettings.decode(s.encode()))
        }
    }

    @Test fun shutdownLayerNeedsHideMode() {
        assertFalse(LockSettings(hideApps = false, hideOnShutdown = true).shutdownActive)
        assertTrue(LockSettings(hideApps = true, hideOnShutdown = true).shutdownActive)
        assertFalse(LockSettings(hideApps = true, hideOnShutdown = false).shutdownActive)
    }

    @Test fun badInputIsRejected() {
        listOf("", "0", "1", "2", "011", "true", " 1", "1 ").forEach {
            assertNull("'$it'", LockSettings.decode(it))
        }
    }
}