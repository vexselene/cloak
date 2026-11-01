package io.cloak.os.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.UserManager
import io.cloak.os.bridge.Commands
import io.cloak.os.bridge.PrivacySettings
import io.cloak.os.common.Logx
import io.cloak.os.common.Role

object PolicyManager {
    private const val TAG = "SecondSpace/PolicyManager"
    private const val HIDE_WORK_LAUNCHER = true

    private class StepRunner(private val name: String) {
        private val failed = mutableListOf<String>()

        fun step(label: String, block: () -> Unit) {
            runCatching(block).onFailure {
                failed += label
                Logx.e(TAG, "$name: $label failed", it)
            }
        }

        fun result(): Result<Unit> =
            if (failed.isEmpty()) Result.success(Unit)
            else Result.failure(IllegalStateException("$name failed: ${failed.joinToString()}"))
    }

    private fun runSteps(name: String, body: StepRunner.() -> Unit): Result<Unit> =
        StepRunner(name).also(body).result()

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)

    fun setupChannel(context: Context): Result<Unit> {
        val dpm = dpm(context)
        val admin = SpaceAdminReceiver.component(context)
        val agent = Role.detect(context) == Role.AGENT
        return runSteps("setupChannel") {
            step("command activity") {
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, CommandActivity::class.java),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            step("intent filter") {
                dpm.clearCrossProfileIntentFilters(admin)
                val filter = IntentFilter(Commands.ACTION).apply { addCategory(Intent.CATEGORY_DEFAULT) }
                dpm.addCrossProfileIntentFilter( // restored bi-directional: space does seems to need to call main
                    admin, filter,
                    DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT or
                            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
                )
            }
            step("work lock") { requireWorkLock(context).getOrThrow() }
            step("installs") { allowInstalls(context).getOrThrow() }
            step("launcher icon") {
                if (agent) {
                    context.packageManager.setComponentEnabledSetting(
                        ComponentName(context.packageName, "io.cloak.os.LauncherAlias"),
                        if (HIDE_WORK_LAUNCHER) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
            step("privacy") {
                if (agent) applyPrivacy(context, PrivacySettings.load(context)).getOrThrow()
            }
        }.onSuccess { Logx.d(TAG, "channel set up") }
    }

    fun requireWorkLock(context: Context): Result<Unit> = runCatching {
        dpm(context).setRequiredPasswordComplexity(DevicePolicyManager.PASSWORD_COMPLEXITY_LOW)
    }.onFailure { Logx.e(TAG, "requireWorkLock failed", it) }

    fun lockNow(context: Context): Result<Unit> = runCatching {
        dpm(context).lockNow(DevicePolicyManager.FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY)
    }.onFailure { Logx.e(TAG, "lockNow failed", it) }

    /** Needed so apps can be cloned in by sideloading APKs. See docs/THREAT_MODEL.md. */
    fun allowInstalls(context: Context): Result<Unit> = runCatching {
        val dpm = dpm(context)
        val admin = SpaceAdminReceiver.component(context)
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
    }.onFailure { Logx.e(TAG, "allowInstalls failed", it) }

    fun applyPrivacy(context: Context, s: PrivacySettings): Result<Unit> {
        val dpm = dpm(context)
        val admin = SpaceAdminReceiver.component(context)
        return runSteps("applyPrivacy") {
            step("screenshots") { dpm.setScreenCaptureDisabled(admin, s.blockScreenshots) }
            step("notifications") {
                dpm.setKeyguardDisabledFeatures(
                    admin,
                    if (s.hideNotificationContent) DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS
                    else DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE
                )
            }
            step("clipboard") {
                if (s.blockClipboard) dpm.addUserRestriction(admin, UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE)
                else dpm.clearUserRestriction(admin, UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE)
            }
            step("contacts") {
                dpm.setCrossProfileContactsSearchDisabled(admin, s.blockContacts)
                dpm.setCrossProfileCallerIdDisabled(admin, s.blockContacts)
            }
        }.onSuccess { Logx.d(TAG, "privacy applied: ${s.encode()}") }
    }

    fun deleteSpace(context: Context): Result<Unit> = runCatching {
        val dpm = dpm(context)
        check(dpm.isProfileOwnerApp(context.packageName)) { "not profile owner" }
        dpm.wipeData(0)
    }.onFailure { Logx.e(TAG, "deleteSpace failed", it) }

    fun enableSystemApp(context: Context, packageName: String): Result<Unit> = runCatching {
        dpm(context).enableSystemApp(SpaceAdminReceiver.component(context), packageName)
    }.onFailure { Logx.e(TAG, "enableSystemApp failed", it) }

    fun setFrozen(context: Context, packageName: String, frozen: Boolean): Result<Unit> = runCatching {
        val failed = dpm(context).setPackagesSuspended(
            SpaceAdminReceiver.component(context), arrayOf(packageName), frozen
        )
        check(failed.isEmpty()) { "package could not be changed" }
    }.onFailure { Logx.e(TAG, "setFrozen failed", it) }
}