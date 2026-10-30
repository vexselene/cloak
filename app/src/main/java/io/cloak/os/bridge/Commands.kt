package io.cloak.os.bridge

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

object Commands {
    const val ACTION = "io.cloak.os.action.COMMAND"
    const val EXTRA_CMD = "cmd"
    const val EXTRA_ARG = "arg"
    const val PING = "ping"
    const val LOCK_NOW = "lock_now"
    const val SET_LOCK = "set_lock"
    const val CLONE = "clone"
    const val FILE_TEST = "file_test"
    const val ENABLE_SYSTEM = "enable_system"
    const val UNLOCK = "unlock"

    fun send(
        context: Context,
        cmd: String,
        arg: String = "",
        uris: List<Uri> = emptyList()
    ): Result<Unit> = runCatching {
        val ts = System.currentTimeMillis()
        val sig = Auth.sign(context, cmd, arg, ts) ?: error("space not paired: no secret")
        val intent = Intent(ACTION)
            .putExtra(EXTRA_CMD, cmd)
            .putExtra(EXTRA_ARG, arg)
            .putExtra(Auth.EXTRA_TS, ts)
            .putExtra(Auth.EXTRA_SIG, sig)
        if (uris.isNotEmpty()) {
            val clip = ClipData.newRawUri("bridge", uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            intent.clipData = clip
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        Unit
    }
}