package io.cloak.os.bridge

import android.content.Context

data class TransferSettings(val shareFromSpace: Boolean = false) {
    fun encode(): String = if (shareFromSpace) "1" else "0"

    companion object {
        fun decode(s: String): TransferSettings? = when (s) {
            "1" -> TransferSettings(true)
            "0" -> TransferSettings(false)
            else -> null
        }

        private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("transfer", Context.MODE_PRIVATE)

        fun load(context: Context) = TransferSettings(prefs(context).getBoolean("share", false))

        fun save(context: Context, settings: TransferSettings) {
            prefs(context).edit().putBoolean("share", settings.shareFromSpace).commit()
        }
    }
}