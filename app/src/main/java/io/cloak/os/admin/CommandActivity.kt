package io.cloak.os.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import io.cloak.os.bridge.Auth
import io.cloak.os.bridge.Commands

class CommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cmdName = intent.getStringExtra(Commands.EXTRA_CMD)
        val arg = intent.getStringExtra(Commands.EXTRA_ARG) ?: ""
        val ts = intent.getLongExtra(Auth.EXTRA_TS, 0L)
        val sig = intent.getStringExtra(Auth.EXTRA_SIG)
        Log.d("SecondSpace/Command", "received cmd=$cmdName sigPresent=${sig != null}")
        if (cmdName == null || !Auth.verify(this, cmdName, arg, ts, sig)) {
            Log.w("SecondSpace/Command", "rejected unsigned or invalid command")
            finish()
            return
        }
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm.isProfileOwnerApp(packageName)) {
            when (cmdName) {
                Commands.PING -> toast("Ping received in Second Space")
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
                Commands.UNLOCK -> Log.d("SecondSpace/Command", "unlock requested")
                Commands.ENABLE_SYSTEM -> {
                    val ok = PolicyManager.enableSystemApp(this, arg).isSuccess
                    toast(if (ok) "Enabled $arg" else "Enable failed: $arg")
                }
                Commands.CLONE -> {
                    val clip = intent.clipData
                    val uris = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
                    installInBackground(arg, uris)
                    return
                }
                Commands.FILE_TEST -> {
                    val uri = intent.clipData?.getItemAt(0)?.uri
                    val text = uri?.let {
                        runCatching { contentResolver.openInputStream(it)?.bufferedReader()?.readText() }.getOrNull()
                    }
                    Log.d("SecondSpace/Command", "file test uri=$uri content=$text")
                    toast(if (text != null) "Got file: $text" else "File read failed")
                }
                else -> Log.w("SecondSpace/Command", "unknown command: $cmdName")
            }
        }
        finish()
    }

    private fun installInBackground(pkg: String, uris: List<Uri>) {
        Thread {
            val result = ApkInstaller.install(this, pkg, uris)
            if (result.isSuccess) {
                InstallCoordinator.await(60_000)
            } else {
                runOnUiThread { toast("Clone failed: ${result.exceptionOrNull()?.message}") }
            }
            runOnUiThread { finish() }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}