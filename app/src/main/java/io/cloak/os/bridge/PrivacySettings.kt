package io.cloak.os.bridge

import android.content.Context

data class PrivacySettings(
    val blockScreenshots: Boolean = false,
    val hideNotificationContent: Boolean = true,
    val blockClipboard: Boolean = false,
    val blockContacts: Boolean = true
) {
    fun encode(): String =
        listOf(blockScreenshots, hideNotificationContent, blockClipboard, blockContacts)
            .joinToString("") { if (it) "1" else "0" }

    companion object {
        fun decode(s: String): PrivacySettings? {
            if (s.length != 4 || s.any { it != '0' && it != '1' }) return null
            return PrivacySettings(s[0] == '1', s[1] == '1', s[2] == '1', s[3] == '1')
        }

        private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("privacy", Context.MODE_PRIVATE)

        fun load(context: Context): PrivacySettings =
            decode(prefs(context).getString("v", "") ?: "") ?: PrivacySettings()

        fun save(context: Context, settings: PrivacySettings) {
            prefs(context).edit().putString("v", settings.encode()).commit()
        }
    }
}