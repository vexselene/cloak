package io.cloak.os.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.cloak.os.common.Logx
import io.cloak.os.common.Role

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Role.detect(context) != Role.AGENT) return
        Logx.d("SecondSpace/Receiver", "setup triggered by ${intent.action}")
        PolicyManager.setupChannel(context)
    }
}