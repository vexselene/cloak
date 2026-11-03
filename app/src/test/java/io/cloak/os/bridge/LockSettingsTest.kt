package io.cloak.os.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockSettingsTest {
    @Test fun defaultsAreHideOffBootFixOn() {
        assertEquals("001", LockSettings().encode())
    }

    @Test fun everyCombinationSurvivesRoundTrip() {
        for (bits in 0 until 8) {
            val s = LockSettings(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
            assertEquals(s, LockSettings.decode(s.encode()))
        }
    }

    @Test fun shutdownLayerNeedsHideMode() {
        assertFalse(LockSettings(hideApps = false, hideOnShutdown = true).shutdownActive)
        assertTrue(LockSettings(hideApps = true, hideOnShutdown = true).shutdownActive)
        assertFalse(LockSettings(hideApps = true, hideOnShutdown = false).shutdownActive)
    }

    @Test fun badInputIsRejected() {
        listOf("", "0", "00", "0011", "002", "true", " 11", "11 ").forEach {
            assertNull("'$it'", LockSettings.decode(it))
        }
    }
}