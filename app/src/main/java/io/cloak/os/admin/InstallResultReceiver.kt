package io.cloak.os.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import io.cloak.os.R
import io.cloak.os.common.Logx

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Logx.d(TAG, "install status=$status")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { Logx.e(TAG, "confirm launch failed", it) }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, R.string.toast_cloned, Toast.LENGTH_SHORT).show()
            else ->
                Toast.makeText(context, R.string.toast_clone_failed, Toast.LENGTH_LONG).show()
        }
        InstallCoordinator.release()
    }

    private companion object {
        const val TAG = "SecondSpace/Installer"
    }
}