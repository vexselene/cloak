package io.cloak.os.bridge

import android.content.Context
import android.content.Intent
import io.cloak.os.common.Logx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hints from the space to the main copy. An event only means "come forward and read the real
 * state". It carries no authority and is not signed.
 */
object SpaceEvents {
    const val UNLOCKED = "unlocked"
    private const val TAG = "SecondSpace/Event"
    private val KNOWN = setOf(UNLOCKED)

    private val _pulse = MutableStateFlow(0)

    /** Counts events received by the main copy, so the UI can re-read the state. */
    val pulse: StateFlow<Int> = _pulse.asStateFlow()

    fun isKnown(name: String?): Boolean = name != null && name in KNOWN

    fun notifyMain() {
        _pulse.value = _pulse.value + 1
    }

    /** Work copy: asks the main copy to come to the front. */
    fun sendToMain(context: Context, name: String) {
        require(isKnown(name))
        val intent = Intent(Commands.EVENT_ACTION)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .putExtra(Commands.EXTRA_EVENT, name)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        runCatching { context.startActivity(intent) }
            .onSuccess { Logx.d(TAG, "sent $name to main") }
            .onFailure { Logx.e(TAG, "could not reach main", it) }
    }
}