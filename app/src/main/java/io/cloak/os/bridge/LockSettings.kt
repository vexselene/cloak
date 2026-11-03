package io.cloak.os.bridge

import android.content.Context

data class LockSettings(val hideApps: Boolean = false) {
    fun encode(): String = if (hideApps) "1" else "0"

    companion object {
        fun decode(s: String): LockSettings? = when (s) {
            "1" -> LockSettings(true)
            "0" -> LockSettings(false)
            else -> null
        }

        private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("lock", Context.MODE_PRIVATE)

        fun load(context: Context): LockSettings =
            LockSettings(prefs(context).getBoolean("hide", false))

        fun save(context: Context, settings: LockSettings) {
            prefs(context).edit().putBoolean("hide", settings.hideApps).commit()
        }
    }
}