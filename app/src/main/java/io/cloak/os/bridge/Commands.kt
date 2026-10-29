package io.cloak.os.bridge

import android.content.Context
import android.content.Intent

object Commands {
    const val ACTION = "io.cloak.os.action.COMMAND"
    const val EXTRA_CMD = "cmd"
    const val EXTRA_ARG = "arg"
    const val PING = "ping"
    const val LOCK_NOW = "lock_now"
    const val SET_LOCK = "set_lock"
    const val CLONE = "clone"

    fun send(context: Context, cmd: String, arg: String = ""): Result<Unit> = runCatching {
        val ts = System.currentTimeMillis()
        val sig = Auth.sign(context, cmd, arg, ts) ?: error("space not paired: no secret")
        context.startActivity(
            Intent(ACTION)
                .putExtra(EXTRA_CMD, cmd)
                .putExtra(EXTRA_ARG, arg)
                .putExtra(Auth.EXTRA_TS, ts)
                .putExtra(Auth.EXTRA_SIG, sig)
        )
        Unit
    }
}