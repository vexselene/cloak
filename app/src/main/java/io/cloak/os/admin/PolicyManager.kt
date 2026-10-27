package io.cloak.os.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log

object PolicyManager {
    private const val TAG = "SecondSpace/PolicyManager"

    fun requireWorkLock(context: Context): Result<Unit> = runCatching {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        dpm.setRequiredPasswordComplexity(DevicePolicyManager.PASSWORD_COMPLEXITY_LOW)
        Log.d(TAG, "Required password complexity set to LOW")
        Unit
    }.onFailure { Log.e(TAG, "requireWorkLock failed", it) }
}