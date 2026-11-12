package io.cloak.os.ui

import io.cloak.os.common.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val TAG = "SecondSpace/Unhide"

object UnhideSettle {
    /** Waits between refresh attempts. About 20 seconds in total, so slow phones get time. */
    val DELAYS_MS = longArrayOf(300, 500, 700, 1_000, 1_500, 2_000, 2_500, 3_000, 4_000, 5_000)

    /**
     * With a count from the space: done once at least that many apps are visible.
     * Without one: done once the list is not empty and did not change since the last look.
     */
    fun settled(visible: Int, expected: Int?, previous: Int?): Boolean = when {
        expected != null -> visible >= expected
        else -> visible > 0 && visible == previous
    }
}

/** Asks every app list on screen to re-read itself, and says whether a settle is running. */
object AppListRefresh {
    private val _pulse = MutableStateFlow(0)
    val pulse: StateFlow<Int> = _pulse.asStateFlow()

    private val _settling = MutableStateFlow(false)
    val settling: StateFlow<Boolean> = _settling.asStateFlow()

    private var active = 0

    fun request() {
        _pulse.value = _pulse.value + 1
    }

    fun begin() {
        active++
        _settling.value = true
    }

    fun end() {
        active = (active - 1).coerceAtLeast(0)
        _settling.value = active > 0
    }
}

/** Refreshes the app lists until [count] reaches [expected], or the schedule runs out. */
suspend fun settleAppList(count: () -> Int, expected: Int?) {
    AppListRefresh.begin()
    try {
        var previous: Int? = null
        AppListRefresh.request()
        for ((i, wait) in UnhideSettle.DELAYS_MS.withIndex()) {
            val visible = withContext(Dispatchers.Default) { count() }
            Logx.d(TAG, "attempt $i: expected=$expected visible=$visible")
            if (UnhideSettle.settled(visible, expected, previous)) return
            previous = visible
            delay(wait)
            AppListRefresh.request()
        }
    } finally {
        AppListRefresh.request()
        AppListRefresh.end()
    }
}