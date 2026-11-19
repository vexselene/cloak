package io.cloak.os.debug

import android.app.Activity
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import io.cloak.os.admin.SpaceAdminReceiver

class PinProbeActivity : Activity() {
    private var startedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After a recreation the earlier prompt is still pending and will deliver its result here.
        if (savedInstanceState != null) return

        val km = getSystemService(KeyguardManager::class.java)
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val um = getSystemService(UserManager::class.java)
        val unified = runCatching { dpm.isUsingUnifiedPassword(SpaceAdminReceiver.component(this)) }
            .getOrNull()
        Log.d(
            TAG,
            "pin probe: deviceSecure=${km.isDeviceSecure} keyguardSecure=${km.isKeyguardSecure} " +
                    "unifiedWithPhoneLock=$unified profileUnlocked=${um.isUserUnlocked}"
        )

        @Suppress("DEPRECATION")
        val intent = km.createConfirmDeviceCredentialIntent(
            "Second Space recovery test",
            "Confirm your work PIN"
        )
        if (intent == null) {
            report("no prompt: the profile has no secure lock")
            finish()
            return
        }
        startedAt = SystemClock.elapsedRealtime()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST) {
            val ms = SystemClock.elapsedRealtime() - startedAt
            val label = when (resultCode) {
                RESULT_OK -> "OK, PIN confirmed"
                RESULT_CANCELED -> "cancelled"
                else -> "unexpected code $resultCode"
            }
            report("$label after $ms ms")
        }
        finish()
    }

    private fun report(message: String) {
        Log.d(TAG, "pin probe result: $message")
        Toast.makeText(this, "PIN probe: $message", Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "SecondSpace/Spike"
        const val REQUEST = 1
    }
}