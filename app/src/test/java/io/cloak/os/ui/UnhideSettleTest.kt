package io.cloak.os.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnhideSettleTest {
    @Test fun withACountItIsDoneOnceEnoughAppsAreVisible() {
        assertFalse(UnhideSettle.settled(3, 5, null))
        assertTrue(UnhideSettle.settled(5, 5, null))
        assertTrue(UnhideSettle.settled(6, 5, 3))
    }

    @Test fun anExpectedCountOfZeroIsDoneAtOnce() {
        assertTrue(UnhideSettle.settled(0, 0, null))
    }

    @Test fun withoutACountItNeedsANonEmptyListThatStoppedChanging() {
        assertFalse(UnhideSettle.settled(0, null, null))
        assertFalse(UnhideSettle.settled(0, null, 0))
        assertFalse(UnhideSettle.settled(4, null, null))
        assertFalse(UnhideSettle.settled(4, null, 3))
        assertTrue(UnhideSettle.settled(4, null, 4))
    }

    @Test fun theScheduleIsAboutTwentySecondsAndEveryDelayIsPositive() {
        assertTrue(UnhideSettle.DELAYS_MS.sum() in 15_000L..30_000L)
        assertTrue(UnhideSettle.DELAYS_MS.all { it > 0 })
    }
}