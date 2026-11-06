package io.cloak.os.transfer

import androidx.annotation.StringRes
import io.cloak.os.R

@StringRes
fun transferTitle(phase: Phase): Int = when (phase) {
    Phase.PREPARING -> R.string.transfer_preparing
    Phase.COPYING -> R.string.transfer_notif_title
    Phase.DONE -> R.string.transfer_done
    Phase.CANCELLED -> R.string.transfer_cancelled
    Phase.FAILED -> R.string.transfer_failed
}