package io.cloak.os.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceEventsTest {
    @Test fun onlyKnownNamesAreAccepted() {
        assertTrue(SpaceEvents.isKnown(SpaceEvents.UNLOCKED))
        listOf(null, "", "Unlocked", "unlock", "lock_now", "unlocked ").forEach {
            assertFalse("'$it'", SpaceEvents.isKnown(it))
        }
    }

    @Test fun eachEventBumpsThePulse() {
        val before = SpaceEvents.pulse.value
        SpaceEvents.notifyMain()
        SpaceEvents.notifyMain()
        assertTrue(SpaceEvents.pulse.value == before + 2)
    }
}