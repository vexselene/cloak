package io.cloak.os.bridge

import android.content.Context
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.os.UserManager
import io.cloak.os.common.Logx

object SpaceProfile {
    private const val TAG = "SecondSpace/Profile"

    fun find(context: Context): UserHandle? {
        val cpa = context.getSystemService(CrossProfileApps::class.java)
        val la = context.getSystemService(LauncherApps::class.java)
        return cpa.targetUserProfiles.firstOrNull { isManaged(la, it) }
    }

    private fun isManaged(la: LauncherApps, user: UserHandle): Boolean {
        val type = runCatching { la.getLauncherUserInfo(user)?.userType }.getOrNull() // requires min api 35
        if (type == null) {
            Logx.d(TAG, "user type unknown, assuming managed")
            return true
        }
        return type == UserManager.USER_TYPE_PROFILE_MANAGED
    }
}