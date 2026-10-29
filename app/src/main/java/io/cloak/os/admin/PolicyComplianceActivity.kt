package io.cloak.os.admin

import android.app.Activity
import android.os.Bundle

class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpaceAdminReceiver.enableProfile(this)

        intent.getParcelableExtra<android.os.PersistableBundle>(
            android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
        )?.getString(io.cloak.os.bridge.Auth.BUNDLE_SECRET)?.let {
            io.cloak.os.bridge.Auth.saveSecret(this, it)
            android.util.Log.d("SecondSpace/Compliance", "secret stored via compliance activity")
        }

        setResult(RESULT_OK)
        finish()
    }
}