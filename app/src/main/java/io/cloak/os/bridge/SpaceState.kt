package io.cloak.os.bridge

import android.content.Context
import android.content.pm.CrossProfileApps
import android.os.UserManager

enum class SpaceState { NONE, PAUSED, LOCKED, UNLOCKED }

object SpaceStateReader {
    fun read(context: Context): SpaceState {
        val cpa = context.getSystemService(CrossProfileApps::class.java)
        val um = context.getSystemService(UserManager::class.java)
        val profile = cpa.targetUserProfiles.firstOrNull() ?: return SpaceState.NONE
        return when {
            um.isQuietModeEnabled(profile) -> SpaceState.PAUSED
            !um.isUserUnlocked(profile) -> SpaceState.LOCKED
            else -> SpaceState.UNLOCKED
        }
    }
}