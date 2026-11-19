package io.cloak.os.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.cloak.os.bridge.LockSettings
import io.cloak.os.common.Logx
import io.cloak.os.common.Role

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Role.detect(context) != Role.AGENT) return
        val app = context.applicationContext
        val action = intent.action
        val boot = action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                action == Intent.ACTION_BOOT_COMPLETED
        Thread {
            Logx.d("SecondSpace/Receiver", "setup triggered by $action")
            if (boot) SessionManager.reportShutdown(app)
            if (!boot || LockSettings.load(app).bootFix) SessionManager.reconcile(app)
            PolicyManager.setupChannel(app)
        }.start()
    }
}