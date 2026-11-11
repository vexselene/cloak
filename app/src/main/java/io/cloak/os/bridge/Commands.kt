package io.cloak.os.bridge

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

object Commands {
    const val ACTION = "io.cloak.os.action.COMMAND"
    const val EXTRA_CMD = "cmd"
    const val EXTRA_ARG = "arg"
    const val PING = "ping"
    const val LOCK_NOW = "lock_now"
    const val SET_LOCK = "set_lock"
    const val CLONE = "clone"
    const val FILE_TEST = "file_test"
    const val ENABLE_SYSTEM = "enable_system"
    const val UNLOCK = "unlock"
    const val FREEZE = "freeze"
    const val UNFREEZE = "unfreeze"
    const val UNINSTALL = "uninstall"
    const val SET_PRIVACY = "set_privacy"
    const val DELETE_SPACE = "delete_space"
    const val REPAIR = "repair"
    const val SPIKE = "spike"
    const val SESSION = "session"
    const val SET_LOCK_MODE = "set_lock_mode"
    const val SET_RECOVERY = "set_recovery"
    const val CLEAR_RECOVERY = "clear_recovery"
    const val RECOVER = "recover"
    const val SET_SHARE = "set_share"
    const val TRANSFER = "transfer"
    const val TRANSFER_PICK = "transfer_pick"
    const val TRANSFER_REPORT = "transfer_report"
    const val TRANSFER_DONE = "transfer_done"
    const val EXTRA_REPORT = "report"
    const val EVENT_ACTION = "io.cloak.os.action.SPACE_EVENT"
    const val EXTRA_EVENT = "event"
    const val LOCK_STATUS = "lock_status"
    const val EXTRA_LOCK_SEPARATE = "separate"
    val TRANSFER_KINDS = setOf("files", "folder")
    val TAKES_PACKAGE = setOf(CLONE, ENABLE_SYSTEM, FREEZE, UNFREEZE, UNINSTALL)

    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun isValidPackage(name: String) = name.length <= 255 && PACKAGE_NAME.matches(name)

    fun build(
        context: Context,
        cmd: String,
        arg: String = "",
        uris: List<Uri> = emptyList()
    ): Intent {
        val signed = Auth.sign(context, cmd, arg) ?: error("space not paired: no secret")
        val intent = Intent(ACTION)
            .putExtra(EXTRA_CMD, cmd)
            .putExtra(EXTRA_ARG, arg)
            .putExtra(Auth.EXTRA_SEQ, signed.seq)
            .putExtra(Auth.EXTRA_SIG, signed.sig)
        if (uris.isNotEmpty()) {
            val clip = ClipData.newRawUri("bridge", uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            intent.clipData = clip
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return intent
    }

    fun send(
        context: Context,
        cmd: String,
        arg: String = "",
        uris: List<Uri> = emptyList()
    ): Result<Unit> = runCatching {
        context.startActivity(build(context, cmd, arg, uris))
    }
}