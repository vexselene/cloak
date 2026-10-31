package io.cloak.os.admin

import android.app.Activity
import android.os.Bundle
import io.cloak.os.bridge.Auth

class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Auth.storeFromProvisioning(this, intent)
        SpaceAdminReceiver.enableProfile(this)
        setResult(RESULT_OK)
        finish()
    }
}