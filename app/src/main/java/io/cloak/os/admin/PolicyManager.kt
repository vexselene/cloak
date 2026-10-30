package io.cloak.os.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import io.cloak.os.bridge.Commands
import android.os.UserManager
import io.cloak.os.bridge.PrivacySettings

object PolicyManager {
    private const val TAG = "SecondSpace/PolicyManager"
    private const val HIDE_WORK_LAUNCHER = true

    fun requireWorkLock(context: Context): Result<Unit> = runCatching {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        dpm.setRequiredPasswordComplexity(DevicePolicyManager.PASSWORD_COMPLEXITY_LOW)
        Log.d(TAG, "Required password complexity set to LOW")
        Unit
    }.onFailure { Log.e(TAG, "requireWorkLock failed", it) }

    fun allowCrossProfile(context: Context): Result<Unit> = runCatching {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        dpm.setCrossProfilePackages(
            SpaceAdminReceiver.component(context),
            setOf(context.packageName)
        )
        Log.d(TAG, "Cross-profile packages set")
        Unit
    }.onFailure { Log.e(TAG, "allowCrossProfile failed", it) }

    fun setupChannel(context: Context): Result<Unit> = runCatching {
        applyPrivacy(context, PrivacySettings.load(context))
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = SpaceAdminReceiver.component(context)
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, CommandActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        dpm.clearCrossProfileIntentFilters(admin)
        val filter = IntentFilter(Commands.ACTION).apply { addCategory(Intent.CATEGORY_DEFAULT) }

        dpm.addCrossProfileIntentFilter(admin, filter, DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT or DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)
        requireWorkLock(context)
        allowInstalls(context)
        if (io.cloak.os.common.Role.detect(context) == io.cloak.os.common.Role.AGENT) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context.packageName, "io.cloak.os.LauncherAlias"),
                if (HIDE_WORK_LAUNCHER) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        Log.d(TAG, "Channel set up")
        Unit
    }.onFailure { Log.e(TAG, "setupChannel failed", it) }

    fun lockNow(context: Context): Result<Unit> = runCatching {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        dpm.lockNow(DevicePolicyManager.FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY)
        Log.d(TAG, "lockNow issued")
        Unit
    }.onFailure { Log.e(TAG, "lockNow failed", it) }

    fun allowInstalls(context: Context): Result<Unit> = runCatching {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = SpaceAdminReceiver.component(context)
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
        Log.d(TAG, "Install restrictions cleared")
        Unit
    }.onFailure { Log.e(TAG, "allowInstalls failed", it) }

    fun cloneApp(context: Context, packageName: String): Result<Boolean> = runCatching {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val ok = dpm.installExistingPackage(SpaceAdminReceiver.component(context), packageName)
        Log.d(TAG, "installExistingPackage($packageName) = $ok")
        ok
    }.onFailure { Log.e(TAG, "cloneApp failed", it) }

    fun enableSystemApp(context: Context, packageName: String): Result<Unit> = runCatching {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        dpm.enableSystemApp(SpaceAdminReceiver.component(context), packageName)
        Log.d(TAG, "enableSystemApp($packageName) ok")
        Unit
    }.onFailure { Log.e(TAG, "enableSystemApp failed", it) }

    fun setFrozen(context: Context, packageName: String, frozen: Boolean): Result<Unit> = runCatching {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val failed = dpm.setPackagesSuspended(
            SpaceAdminReceiver.component(context), arrayOf(packageName), frozen
        )
        if (failed.isNotEmpty()) error("could not change: ${failed.joinToString()}")
        Log.d(TAG, "setFrozen($packageName, $frozen) ok")
        Unit
    }.onFailure { Log.e(TAG, "setFrozen failed", it) }

    fun applyPrivacy(context: Context, s: PrivacySettings): Result<Unit> = runCatching {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = SpaceAdminReceiver.component(context)
        val steps = listOf(
            "screenshots" to runCatching { dpm.setScreenCaptureDisabled(admin, s.blockScreenshots) },
            "notifications" to runCatching {
                dpm.setKeyguardDisabledFeatures(
                    admin,
                    if (s.hideNotificationContent) DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS
                    else DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE
                )
            },
            "clipboard" to runCatching {
                if (s.blockClipboard) dpm.addUserRestriction(admin, UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE)
                else dpm.clearUserRestriction(admin, UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE)
            },
            "contacts" to runCatching {
                dpm.setCrossProfileContactsSearchDisabled(admin, s.blockContacts)
                dpm.setCrossProfileCallerIdDisabled(admin, s.blockContacts)
            }
        )
        val failed = steps.filter { it.second.isFailure }
        failed.forEach { Log.e(TAG, "privacy ${it.first} failed", it.second.exceptionOrNull()) }
        if (failed.isNotEmpty()) error("failed: ${failed.joinToString { it.first }}")
        Log.d(TAG, "Privacy applied: ${s.encode()}")
        Unit
    }.onFailure { Log.e(TAG, "applyPrivacy failed", it) }

    fun deleteSpace(context: Context): Result<Unit> = runCatching {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        check(dpm.isProfileOwnerApp(context.packageName)) { "not profile owner" }
        dpm.wipeData(0)
        Unit
    }.onFailure { Log.e(TAG, "deleteSpace failed", it) }
}