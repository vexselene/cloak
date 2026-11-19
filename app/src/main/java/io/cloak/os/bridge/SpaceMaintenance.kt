package io.cloak.os.bridge

import android.content.Context

object SpaceMaintenance {
    /** Succeeds only if the command channel resolves in the space. */
    fun check(context: Context): Result<Unit> {
        val state = SpaceStateReader.read(context)
        if (state == SpaceState.PAUSED || state == SpaceState.LOCKED) {
            return Result.failure(IllegalStateException("space is not reachable: $state"))
        }
        return Commands.send(context, Commands.PING)
    }

    /** Asks the space copy to re-run its full setup. */
    fun repair(context: Context): Result<Unit> = Commands.send(context, Commands.REPAIR)
}