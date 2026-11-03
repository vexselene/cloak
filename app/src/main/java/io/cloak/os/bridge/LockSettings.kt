package io.cloak.os.bridge

import android.content.Context

data class LockSettings(
    val hideApps: Boolean = false,
    val hideOnShutdown: Boolean = false
) {
    /** The shutdown layer only means something while hide mode is on. */
    val shutdownActive: Boolean get() = hideApps && hideOnShutdown

    fun encode(): String =
        listOf(hideApps, hideOnShutdown).joinToString("") { if (it) "1" else "0" }

    companion object {
        fun decode(s: String): LockSettings? {
            if (s.length != 2 || s.any { it != '0' && it != '1' }) return null
            return LockSettings(s[0] == '1', s[1] == '1')
        }

        private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("lock", Context.MODE_PRIVATE)

        fun load(context: Context): LockSettings {
            val p = prefs(context)
            return LockSettings(p.getBoolean("hide", false), p.getBoolean("shutdown", false))
        }

        fun save(context: Context, settings: LockSettings) {
            prefs(context).edit()
                .putBoolean("hide", settings.hideApps)
                .putBoolean("shutdown", settings.hideOnShutdown)
                .commit()
        }
    }
}