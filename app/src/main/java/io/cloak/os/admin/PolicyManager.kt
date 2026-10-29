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

object PolicyManager {
    private const val TAG = "SecondSpace/PolicyManager"

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
}