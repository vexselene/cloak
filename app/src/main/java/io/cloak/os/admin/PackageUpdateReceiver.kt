package io.cloak.os.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.cloak.os.common.Role

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Role.detect(context) == Role.AGENT) PolicyManager.setupChannel(context)
    }
}