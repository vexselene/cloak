package io.cloak.os.debug

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserManager
import android.util.Log
import io.cloak.os.bridge.Auth
import io.cloak.os.bridge.Commands

object OwnershipProbe {
    private const val TAG = "SecondSpace/Probe"

    fun run(context: Context): String {
        val um = context.getSystemService(UserManager::class.java)
        val la = context.getSystemService(LauncherApps::class.java)
        val cpa = context.getSystemService(CrossProfileApps::class.java)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val out = StringBuilder()

        val profiles = runCatching { um.userProfiles }.getOrNull()
        out.appendLine("userProfiles: ${profiles?.size ?: "unavailable"}")
        profiles?.forEach { u ->
            val type = runCatching { la.getLauncherUserInfo(u)?.userType }.getOrNull()
            val installed = runCatching { la.getApplicationInfo(context.packageName, 0, u); true }
                .getOrDefault(false)
            out.appendLine("  $u type=$type cloakInstalled=$installed self=${u == Process.myUserHandle()}")
        }
        out.appendLine("cross-profile targets: ${cpa.targetUserProfiles}")

        val intent = Intent(Commands.ACTION).addCategory(Intent.CATEGORY_DEFAULT)
        val hits = runCatching { context.packageManager.queryIntentActivities(intent, 0) }
            .getOrDefault(emptyList())
        out.appendLine(
            "filter resolves: ${hits.size} " +
                    hits.joinToString { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
        )
        out.appendLine("secret present: ${Auth.hasSecret(context)}")
        out.appendLine(
            "provisioning allowed: " +
                    dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        )
        return out.toString().also { Log.d(TAG, it) }
    }
}