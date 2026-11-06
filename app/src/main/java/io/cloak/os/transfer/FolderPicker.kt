package io.cloak.os.transfer

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import io.cloak.os.bridge.Commands

object FolderPicker {
    /** System apps on this side that answer the folder picker. The space needs the same one. */
    fun systemHandlers(context: Context): List<String> =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 0)
            .map { it.activityInfo.applicationInfo }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && it.packageName != context.packageName }
            .map { it.packageName }
            .distinct()

    /** Asks the space to enable the folder picker. True means the request was sent. */
    fun enableInSpace(context: Context): Boolean {
        val handlers = systemHandlers(context)
        val pkg = handlers.firstOrNull { it.contains("documentsui") } ?: handlers.firstOrNull() ?: return false
        return Commands.send(context, Commands.ENABLE_SYSTEM, pkg).isSuccess
    }
}