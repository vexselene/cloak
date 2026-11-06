package io.cloak.os.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.StringRes
import io.cloak.os.BuildConfig
import io.cloak.os.R
import io.cloak.os.bridge.ApkExporter
import io.cloak.os.bridge.Auth
import io.cloak.os.bridge.Commands
import io.cloak.os.bridge.PrivacySettings
import io.cloak.os.common.Logx
import io.cloak.os.debug.SpikeRunner
import io.cloak.os.bridge.LockSettings
import io.cloak.os.bridge.RecoveryStore
import io.cloak.os.bridge.RecoveryToken
import android.app.KeyguardManager
import io.cloak.os.bridge.RecoveryProof
import io.cloak.os.bridge.TransferSettings

class CommandActivity : Activity() {
    private var awaitingPin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // If Android recreates the activity while the PIN prompt is up, the result still arrives and the command isn’t run twice.
        if (savedInstanceState?.getBoolean(STATE_AWAITING) == true) {
            awaitingPin = true
            return
        }
        super.onCreate(savedInstanceState)
        val keepOpen = runCatching { handle() }
            .onFailure { Logx.e(TAG, "command failed", it) }
            .getOrDefault(false)
        if (!keepOpen) finish()
    }

    /** Returns true when the activity must stay alive until background work ends. */
    private fun handle(): Boolean {
        val cmd = intent.getStringExtra(Commands.EXTRA_CMD) ?: return false
        val arg = intent.getStringExtra(Commands.EXTRA_ARG) ?: ""
        val seq = intent.getLongExtra(Auth.EXTRA_SEQ, 0L)
        val sig = intent.getStringExtra(Auth.EXTRA_SIG)

        if (cmd == Commands.RECOVER) return handleRecover()

        if (!Auth.verify(this, cmd, arg, seq, sig)) {
            Logx.w(TAG, "rejected unsigned or invalid command")
            return false
        }
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isProfileOwnerApp(packageName)) return false
        if (cmd in Commands.TAKES_PACKAGE && (!Commands.isValidPackage(arg) || arg == packageName)) {
            Logx.w(TAG, "rejected bad package argument")
            return false
        }

        when (cmd) {
            Commands.PING -> if (BuildConfig.DEBUG) {
                toast("Ping received in Second Space")
                setResult(RESULT_OK, Intent().putExtra("echo", seq))
            }
            Commands.LOCK_NOW -> SessionManager.lock(this)
            Commands.SET_LOCK -> {
                PolicyManager.requireWorkLock(this)
                startActivity(
                    Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD).putExtra(
                        DevicePolicyManager.EXTRA_PASSWORD_COMPLEXITY,
                        DevicePolicyManager.PASSWORD_COMPLEXITY_LOW
                    )
                )
            }
            Commands.UNLOCK -> Logx.d(TAG, "unlock requested")
            Commands.ENABLE_SYSTEM ->
                if (PolicyManager.enableSystemApp(this, arg).isFailure) toast(R.string.toast_action_failed)
            Commands.FREEZE, Commands.UNFREEZE ->
                if (PolicyManager.setFrozen(this, arg, cmd == Commands.FREEZE).isFailure) {
                    toast(R.string.toast_action_failed)
                }
            Commands.UNINSTALL ->
                startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", arg, null)))
            Commands.CLONE -> {
                val uris = bridgeUris()
                if (uris.isEmpty()) {
                    Logx.w(TAG, "clone without valid files")
                    return false
                }
                installInBackground(arg, uris)
                return true
            }
            Commands.FILE_TEST -> if (BuildConfig.DEBUG) fileTest()
            Commands.SET_PRIVACY -> {
                val settings = PrivacySettings.decode(arg)
                if (settings == null) {
                    Logx.w(TAG, "bad privacy argument")
                } else {
                    PrivacySettings.save(this, settings)
                    if (PolicyManager.applyPrivacy(this, settings).isFailure) {
                        toast(R.string.privacy_apply_failed)
                    }
                }
            }
            Commands.DELETE_SPACE -> PolicyManager.deleteSpace(this)
            Commands.REPAIR -> PolicyManager.setupChannel(this)
            Commands.SPIKE -> SpikeRunner.run(this, arg)
            Commands.SESSION -> SessionManager.onAppOpened(this, refresh = arg == "r")
            Commands.SET_LOCK_MODE -> {
                val s = LockSettings.decode(arg)
                if (s == null) {
                    Logx.w(TAG, "bad lock mode argument")
                } else {
                    val old = LockSettings.load(this)
                    LockSettings.save(this, s)
                    SessionManager.onSettingsChanged(this, old, s)
                }
            }
            Commands.SET_RECOVERY ->
                if (RecoveryToken.isValidKey(arg) && RecoveryStore.saveKey(this, arg)) {
                    Logx.d(TAG, "recovery key stored")
                    toast(R.string.toast_recovery_set)
                } else {
                    Logx.w(TAG, "recovery key rejected")
                    toast(R.string.toast_action_failed)
                }
            Commands.CLEAR_RECOVERY ->
                if (RecoveryStore.clearKey(this)) toast(R.string.toast_recovery_cleared)
            Commands.SET_SHARE -> {
                val s = TransferSettings.decode(arg)
                if (s == null) {
                    Logx.w(TAG, "bad share argument")
                } else {
                    TransferSettings.save(this, s)
                    PolicyManager.setupChannel(this)
                }
            }
            else -> Logx.w(TAG, "unknown command")
        }
        return false
    }

    /** Only files from our own FileProvider are accepted. Empty list means reject. */
    private fun bridgeUris(): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        val all = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        val ok = all.isNotEmpty() &&
                all.all { it.scheme == "content" && it.host == ApkExporter.AUTHORITY }
        return if (ok) all else emptyList()
    }

    private fun fileTest() {
        val uri = bridgeUris().firstOrNull()
        val text = uri?.let {
            runCatching { contentResolver.openInputStream(it)?.bufferedReader()?.readText() }.getOrNull()
        }
        toast(if (text != null) "Got file: $text" else "File read failed")
    }

    private fun installInBackground(pkg: String, uris: List<Uri>) {
        Thread {
            val result = ApkInstaller.install(this, pkg, uris)
            if (result.isSuccess) {
                InstallCoordinator.await(60_000)
            } else {
                runOnUiThread { toast(R.string.toast_clone_failed) }
            }
            runOnUiThread { finish() }
        }.start()
    }

    private fun toast(@StringRes id: Int) = Toast.makeText(this, id, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG = "SecondSpace/Command"
        const val STATE_AWAITING = "awaiting_pin"
        const val REQUEST_PIN = 1
    }

    private fun handleRecover(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isProfileOwnerApp(packageName)) return false
        val outcome = RecoveryGate.evaluate(
            this,
            intent.getStringExtra(RecoveryProof.EXTRA_SECRET),
            intent.getLongExtra(RecoveryProof.EXTRA_TS, 0L),
            intent.getStringExtra(RecoveryProof.EXTRA_PROOF)
        )
        Logx.d(TAG, "recovery outcome $outcome")
        when (outcome) {
            RecoveryOutcome.ACCEPTED -> return askForPin()
            RecoveryOutcome.NOT_ENABLED -> toast(R.string.recover_not_enabled)
            RecoveryOutcome.NOT_UNLOCKED -> toast(R.string.recover_not_unlocked)
            RecoveryOutcome.LOCKED_OUT -> toast(R.string.recover_locked_out)
            RecoveryOutcome.EXPIRED -> toast(R.string.recover_expired)
            RecoveryOutcome.BAD_PROOF -> toast(R.string.recover_bad_token)
            RecoveryOutcome.MALFORMED -> Unit
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun askForPin(): Boolean {
        val km = getSystemService(KeyguardManager::class.java)
        val prompt = km.createConfirmDeviceCredentialIntent(
            getString(R.string.recover_pin_title),
            getString(R.string.recover_pin_desc)
        )
        if (prompt == null) {
            RecoveryGate.discard(this)
            toast(R.string.recover_no_lock)
            return false
        }
        awaitingPin = true
        startActivityForResult(prompt, REQUEST_PIN)
        return true
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PIN) return
        awaitingPin = false
        val ok = resultCode == RESULT_OK && RecoveryGate.commit(this)
        if (ok) {
            toast(R.string.recover_done_toast)
            setResult(RESULT_OK)
        } else {
            RecoveryGate.discard(this)
            toast(R.string.recover_cancelled)
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AWAITING, awaitingPin)
    }
}