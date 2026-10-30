package io.cloak.os.bridge

import android.content.Context
import android.content.pm.CrossProfileApps
import android.os.UserManager

enum class SpaceState { NONE, PAUSED, LOCKED, UNLOCKED }

object SpaceStateReader {
    fun read(context: Context): SpaceState {
        val um = context.getSystemService(UserManager::class.java)
        val profile = SpaceProfile.find(context) ?: return SpaceState.NONE
        return when {
            um.isQuietModeEnabled(profile) -> SpaceState.PAUSED
            !um.isUserUnlocked(profile) -> SpaceState.LOCKED
            else -> SpaceState.UNLOCKED
        }
    }
}