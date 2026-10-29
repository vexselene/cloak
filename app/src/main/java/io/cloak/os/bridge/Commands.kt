package io.cloak.os.bridge

import android.content.Context
import android.content.Intent

object Commands {
    const val ACTION = "io.cloak.os.action.COMMAND"
    const val EXTRA_CMD = "cmd"
    const val PING = "ping"
    const val LOCK_NOW = "lock_now"

    fun send(context: Context, cmd: String): Result<Unit> = runCatching {
        val ts = System.currentTimeMillis()
        val sig = Auth.sign(context, cmd, ts) ?: error("space not paired: no secret")
        context.startActivity(
            Intent(ACTION)
                .putExtra(EXTRA_CMD, cmd)
                .putExtra(Auth.EXTRA_TS, ts)
                .putExtra(Auth.EXTRA_SIG, sig)
        )
        Unit
    }
}