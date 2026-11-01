package io.cloak.os.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PrivacySettingsTest {
    @Test fun defaultsEncodeToExpectedString() {
        assertEquals("0101", PrivacySettings().encode())
    }

    @Test fun everyCombinationSurvivesRoundTrip() {
        for (bits in 0 until 16) {
            val s = PrivacySettings(
                blockScreenshots = bits and 1 != 0,
                hideNotificationContent = bits and 2 != 0,
                blockClipboard = bits and 4 != 0,
                blockContacts = bits and 8 != 0
            )
            assertEquals(s, PrivacySettings.decode(s.encode()))
        }
    }

    @Test fun badInputIsRejected() {
        listOf("", "010", "01010", "01a1", "0 01", "2222", "١٠١٠").forEach {
            assertNull("'$it'", PrivacySettings.decode(it))
        }
    }

    @Test fun validInputIsAccepted() {
        assertNotNull(PrivacySettings.decode("1111"))
        assertNotNull(PrivacySettings.decode("0000"))
    }
}