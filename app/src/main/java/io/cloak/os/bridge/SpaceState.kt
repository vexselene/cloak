package io.cloak.os.bridge

import android.content.Context
import android.os.UserManager

enum class SpaceState { NONE, FOREIGN, UNPAIRED, PAUSED, LOCKED, UNLOCKED }

object SpaceStateReader {
    fun read(context: Context): SpaceState {
        val um = context.getSystemService(UserManager::class.java)
        val found = SpaceProfile.detect(context)
        val profile = found.user
        return when {
            found.owner == SpaceOwner.NONE || profile == null -> SpaceState.NONE
            found.owner == SpaceOwner.FOREIGN -> SpaceState.FOREIGN
            !Auth.hasSecret(context) -> SpaceState.UNPAIRED
            um.isQuietModeEnabled(profile) -> SpaceState.PAUSED
            !um.isUserUnlocked(profile) -> SpaceState.LOCKED
            else -> SpaceState.UNLOCKED
        }
    }
}