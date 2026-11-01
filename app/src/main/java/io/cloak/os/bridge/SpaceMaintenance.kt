package io.cloak.os.bridge

import android.content.Context
import android.os.UserManager
import io.cloak.os.common.Logx

object SpaceMaintenance {
    private const val TAG = "SecondSpace/Maintenance"

    /** Succeeds only if the command channel resolves in the space. */
    fun check(context: Context): Result<Unit> = Commands.send(context, Commands.PING)

    /** Pauses the space, which stops the work profile. Resuming restarts it. */
    fun restart(context: Context): Result<Unit> = setPaused(context, true)

    /** Resumes a paused space without needing the command channel. */
    fun resume(context: Context): Result<Unit> = setPaused(context, false)

    private fun setPaused(context: Context, paused: Boolean): Result<Unit> = runCatching {
        val profile = SpaceProfile.find(context) ?: error("no space")
        val um = context.getSystemService(UserManager::class.java)
        check(um.requestQuietModeEnabled(paused, profile)) { "request refused" }
    }.onFailure { Logx.e(TAG, "setPaused($paused) failed", it) }
}