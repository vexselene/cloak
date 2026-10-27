package io.cloak.os.admin

import android.app.Activity
import android.os.Bundle

class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpaceAdminReceiver.enableProfile(this)
        setResult(RESULT_OK)
        finish()
    }
}