package io.cloak.os.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
        val filter = IntentFilter("io.cloak.os.action.PING").apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        }
//        dpm.addCrossProfileIntentFilter(admin, filter, DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)
        dpm.addCrossProfileIntentFilter(admin, filter, DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT or DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)
        Log.d(TAG, "Channel set up")
        Unit
    }.onFailure { Log.e(TAG, "setupChannel failed", it) }
}