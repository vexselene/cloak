package io.cloak.os.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        Log.d("SecondSpace/Installer", "status=$status msg=$msg pkg=$pkg")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { Log.e("SecondSpace/Installer", "confirm launch failed", it) }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, "Cloned $pkg", Toast.LENGTH_SHORT).show()
            else ->
                Toast.makeText(context, "Install failed: $msg", Toast.LENGTH_LONG).show()
        }
        InstallCoordinator.release()
    }
}