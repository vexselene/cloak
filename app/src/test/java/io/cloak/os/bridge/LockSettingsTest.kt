package io.cloak.os.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LockSettingsTest {
    @Test fun defaultIsOff() {
        assertEquals("0", LockSettings().encode())
    }

    @Test fun roundTrip() {
        assertEquals(LockSettings(true), LockSettings.decode(LockSettings(true).encode()))
        assertEquals(LockSettings(false), LockSettings.decode(LockSettings(false).encode()))
    }

    @Test fun badInputIsRejected() {
        listOf("", "2", "01", "true", " 1").forEach { assertNull("'$it'", LockSettings.decode(it)) }
    }
}