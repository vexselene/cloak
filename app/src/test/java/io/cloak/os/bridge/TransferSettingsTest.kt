package io.cloak.os.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferSettingsTest {
    @Test fun defaultIsOff() {
        assertEquals("0", TransferSettings().encode())
    }

    @Test fun roundTrip() {
        assertEquals(TransferSettings(true), TransferSettings.decode(TransferSettings(true).encode()))
        assertEquals(TransferSettings(false), TransferSettings.decode(TransferSettings(false).encode()))
    }

    @Test fun badInputIsRejected() {
        listOf("", "2", "01", "true", " 1").forEach { assertNull("'$it'", TransferSettings.decode(it)) }
    }
}