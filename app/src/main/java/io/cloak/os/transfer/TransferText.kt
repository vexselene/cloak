package io.cloak.os.transfer

import androidx.annotation.StringRes
import io.cloak.os.R

@StringRes
fun transferTitle(s: TransferState): Int = when (s.phase) {
    Phase.PREPARING -> R.string.transfer_preparing
    Phase.COPYING -> R.string.transfer_notif_title
    Phase.SETTLING -> R.string.transfer_settling
    Phase.DONE -> if (s.settle) R.string.transfer_settle_done else R.string.transfer_done
    Phase.CANCELLED -> R.string.transfer_cancelled
    Phase.FAILED -> R.string.transfer_failed
}