package io.cloak.os.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryLockoutTest {
    @Test fun firstThreeFailuresAreFree() {
        for (f in 0..3) assertEquals("failures=$f", 0L, RecoveryLockout.delayMs(f))
    }

    @Test fun delaysGrowThenCap() {
        assertEquals(30_000L, RecoveryLockout.delayMs(4))
        assertEquals(120_000L, RecoveryLockout.delayMs(5))
        assertEquals(600_000L, RecoveryLockout.delayMs(6))
        assertEquals(3_600_000L, RecoveryLockout.delayMs(7))
        assertEquals(21_600_000L, RecoveryLockout.delayMs(8))
        assertEquals(21_600_000L, RecoveryLockout.delayMs(500))
    }

    @Test fun delaysNeverShrink() {
        var last = 0L
        for (f in 0..30) {
            val d = RecoveryLockout.delayMs(f)
            assertTrue("failures=$f", d >= last)
            last = d
        }
    }

    @Test fun negativeFailuresAreHarmless() {
        assertEquals(0L, RecoveryLockout.delayMs(-1))
    }

    @Test fun lockedUntilIsNowPlusDelay() {
        assertEquals(1_000L + 30_000L, RecoveryLockout.lockedUntilAfterFailure(4, 1_000L))
        assertEquals(1_000L, RecoveryLockout.lockedUntilAfterFailure(1, 1_000L))
    }

    @Test fun remainingTimeCountsDown() {
        val until = RecoveryLockout.lockedUntilAfterFailure(4, 1_000L)
        assertEquals(30_000L, RecoveryLockout.remainingMs(4, until, 1_000L))
        assertEquals(10_000L, RecoveryLockout.remainingMs(4, until, 21_000L))
        assertEquals(0L, RecoveryLockout.remainingMs(4, until, until))
        assertEquals(0L, RecoveryLockout.remainingMs(4, until, until + 1))
    }

    @Test fun clockSetBackCannotStretchTheLockout() {
        val now = 1_000_000L
        val until = now + 10L * 24 * 3_600_000L
        assertEquals(30_000L, RecoveryLockout.remainingMs(4, until, now))
        assertEquals(21_600_000L, RecoveryLockout.remainingMs(9, until, now))
    }

    @Test fun noLockoutRecordedMeansNoWait() {
        assertEquals(0L, RecoveryLockout.remainingMs(0, 0L, 5_000L))
    }
}