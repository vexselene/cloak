package io.cloak.os.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.cloak.os.R
import io.cloak.os.bridge.Auth
import io.cloak.os.common.Logx

class SpaceAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Auth.storeFromProvisioning(context, intent)
        enableProfile(context)
    }

    companion object {
        private const val TAG = "SecondSpace/AdminReceiver"

        fun component(context: Context) = ComponentName(context, SpaceAdminReceiver::class.java)

        fun enableProfile(context: Context) {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            if (!dpm.isProfileOwnerApp(context.packageName)) return
            val admin = component(context)
            runCatching { dpm.setProfileName(admin, context.getString(R.string.space_name)) }
                .onFailure { Logx.e(TAG, "setProfileName failed", it) }
            runCatching { dpm.setProfileEnabled(admin) }
                .onFailure { Logx.e(TAG, "setProfileEnabled failed", it) }
            PolicyManager.setupChannel(context)
        }
    }
}