package io.cloak.os.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import io.cloak.os.R

class SpaceAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        storeSecret(context, intent, "onProfileProvisioningComplete")
        Log.d(TAG, "onProfileProvisioningComplete")
        enableProfile(context)
    }

    companion object {
        private const val TAG = "SecondSpace/AdminReceiver"

        fun component(context: Context) = ComponentName(context, SpaceAdminReceiver::class.java)

        fun enableProfile(context: Context) {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            if (!dpm.isProfileOwnerApp(context.packageName)) return
            val admin = component(context)
            dpm.setProfileName(admin, context.getString(R.string.space_name))
            dpm.setProfileEnabled(admin)
        }
    }

    private fun storeSecret(context: Context, intent: Intent, where: String) {
        val extras = intent.getParcelableExtra<android.os.PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
        )
        extras?.getString(io.cloak.os.bridge.Auth.BUNDLE_SECRET)?.let {
            io.cloak.os.bridge.Auth.saveSecret(context, it)
            Log.d(TAG, "secret stored via $where")
        }
    }
}