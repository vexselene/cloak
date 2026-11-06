package io.cloak.os.transfer

import android.content.Intent
import android.net.Uri
import android.os.Build

object ShareIntents {
    /** Private action: the space sends its picked files to Cloak in the personal profile. */
    const val ACTION_TRANSFER_IN = "io.cloak.os.action.TRANSFER_IN"
    const val EXTRA_TRANSFER_ID = "transfer_id"

    @Suppress("DEPRECATION")
    fun sharedUris(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        intent.clipData?.let { c ->
            for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { out.add(it) }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { out.add(it) }
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { out.addAll(it) }
        } else {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.add(it) }
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
        }
        return out.toList()
    }
}