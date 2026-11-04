package io.cloak.os.bridge

import android.content.Context
import android.os.UserManager

enum class SpaceState { NONE, UNPAIRED, PAUSED, LOCKED, UNLOCKED } // new unpaired state

object SpaceStateReader {
    fun read(context: Context): SpaceState {
        val um = context.getSystemService(UserManager::class.java)
        val profile = SpaceProfile.find(context) ?: return SpaceState.NONE
        if (!Auth.hasSecret(context)) return SpaceState.UNPAIRED
        return when {
            um.isQuietModeEnabled(profile) -> SpaceState.PAUSED
            !um.isUserUnlocked(profile) -> SpaceState.LOCKED
            else -> SpaceState.UNLOCKED
        }
    }
}