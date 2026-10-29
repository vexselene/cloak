package io.cloak.os.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import io.cloak.os.bridge.Commands
import io.cloak.os.bridge.Auth
import android.content.Intent

class CommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cmdName = intent.getStringExtra(Commands.EXTRA_CMD)
        val ts = intent.getLongExtra(Auth.EXTRA_TS, 0L)
        val sig = intent.getStringExtra(Auth.EXTRA_SIG)
        Log.d("SecondSpace/Command", "v2 received cmd=$cmdName sigPresent=${sig != null}")
        if (cmdName == null || !Auth.verify(this, cmdName, ts, sig)) {
            Log.w("SecondSpace/Command", "rejected unsigned or invalid command")
            finish()
            return
        }
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm.isProfileOwnerApp(packageName)) {
            when (val cmd = intent.getStringExtra(Commands.EXTRA_CMD)) {
                Commands.PING ->
                    Toast.makeText(this, "Ping received in Second Space", Toast.LENGTH_SHORT).show()
                Commands.LOCK_NOW -> PolicyManager.lockNow(this)
                Commands.SET_LOCK -> {
                    PolicyManager.requireWorkLock(this)
                    startActivity(
                        Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD).putExtra(
                            DevicePolicyManager.EXTRA_PASSWORD_COMPLEXITY,
                            DevicePolicyManager.PASSWORD_COMPLEXITY_LOW
                        )
                    )
                }
                else -> Log.w("SecondSpace/Command", "unknown command: $cmd")
            }
        }
        finish()
    }
}