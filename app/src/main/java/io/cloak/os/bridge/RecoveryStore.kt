package io.cloak.os.bridge

import android.content.Context

object RecoveryStore {
    private const val KEY = "key"
    private const val HINT = "enabled_hint"

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("recovery", Context.MODE_PRIVATE)

    /** Work copy only. */
    fun saveKey(context: Context, key: String): Boolean =
        prefs(context).edit().putString(KEY, key).commit()

    fun loadKey(context: Context): String? = prefs(context).getString(KEY, null)

    fun hasKey(context: Context): Boolean = loadKey(context) != null

    fun clearKey(context: Context): Boolean = prefs(context).edit().remove(KEY).commit()

    /** Main copy only. A UI hint, not the source of truth. */
    fun isEnabledHint(context: Context): Boolean = prefs(context).getBoolean(HINT, false)

    fun setEnabledHint(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(HINT, enabled).commit()
    }
}