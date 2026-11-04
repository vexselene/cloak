package io.cloak.os.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

enum class SpaceOwner { NONE, CLOAK, FOREIGN }

class DetectedSpace(val owner: SpaceOwner, val user: UserHandle?, val cloakInside: Boolean)

object SpaceProfile {
    private val NONE = DetectedSpace(SpaceOwner.NONE, null, false)

    /** The space's profile, only when Cloak manages it. */
    fun find(context: Context): UserHandle? =
        detect(context).takeIf { it.owner == SpaceOwner.CLOAK }?.user

    fun detect(context: Context): DetectedSpace {
        val um = context.getSystemService(UserManager::class.java)
        val la = context.getSystemService(LauncherApps::class.java)
        val cpa = context.getSystemService(CrossProfileApps::class.java)
        val targets = cpa.targetUserProfiles
        val me = Process.myUserHandle()
        val managed = um.userProfiles.firstOrNull { it != me && isManaged(la, it, targets) }
            ?: return NONE
        val inside = runCatching { la.getApplicationInfo(context.packageName, 0, managed) }.isSuccess
        val owner = classify(inside, Auth.hasSecret(context)) { filterResolves(context) }
        return DetectedSpace(owner, managed, inside)
    }

    /** Pure decision, so it can be unit tested. The filter check only runs when it must. */
    internal fun classify(
        cloakInside: Boolean,
        hasSecret: Boolean,
        filterResolves: () -> Boolean
    ): SpaceOwner = when {
        !cloakInside -> SpaceOwner.FOREIGN
        hasSecret -> SpaceOwner.CLOAK
        filterResolves() -> SpaceOwner.CLOAK
        else -> SpaceOwner.FOREIGN
    }

    private fun isManaged(la: LauncherApps, user: UserHandle, targets: List<UserHandle>): Boolean {
        val type = runCatching { la.getLauncherUserInfo(user)?.userType }.getOrNull()
            ?: return user in targets
        return type == UserManager.USER_TYPE_PROFILE_MANAGED
    }

    /**
     * Only a profile owner can register a cross-profile filter, and our action is private to
     * Cloak. Any hit outside our own package is the system forwarder, so a Cloak work copy
     * registered it. The main copy's own CommandActivity is disabled, so it never matches.
     */
    private fun filterResolves(context: Context): Boolean {
        val intent = Intent(Commands.ACTION).addCategory(Intent.CATEGORY_DEFAULT)
        return runCatching {
            context.packageManager.queryIntentActivities(intent, 0)
                .any { it.activityInfo.packageName != context.packageName }
        }.getOrDefault(false)
    }
}