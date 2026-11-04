package io.cloak.os.bridge

object RecoveryLockout {
    private val DELAYS_MS = longArrayOf(
        0L, 0L, 0L, 30_000L, 120_000L, 600_000L, 3_600_000L, 21_600_000L
    )

    fun delayMs(failures: Int): Long =
        if (failures <= 0) 0L else DELAYS_MS[minOf(failures, DELAYS_MS.size) - 1]

    fun lockedUntilAfterFailure(failures: Int, now: Long): Long = now + delayMs(failures)

    fun remainingMs(failures: Int, lockedUntil: Long, now: Long): Long {
        if (lockedUntil <= now) return 0L
        return minOf(lockedUntil - now, delayMs(failures))
    }
}