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

    // Space copy: attempt tracking and the pending secret
    fun failures(context: Context): Int = prefs(context).getInt("fails", 0)
    fun lockedUntil(context: Context): Long = prefs(context).getLong("locked_until", 0L)
    fun lastProofTs(context: Context): Long = prefs(context).getLong("last_ts", 0L)

    fun recordFailure(context: Context, failures: Int, lockedUntil: Long): Boolean =
        prefs(context).edit().putInt("fails", failures).putLong("locked_until", lockedUntil).commit()

    fun recordAccepted(context: Context, ts: Long): Boolean =
        prefs(context).edit().putLong("last_ts", ts).putInt("fails", 0).putLong("locked_until", 0L).commit()

    fun setPending(context: Context, secret: String, until: Long): Boolean =
        prefs(context).edit().putString("pending", secret).putLong("pending_until", until).commit()

    fun pendingSecret(context: Context): String? = prefs(context).getString("pending", null)
    fun pendingUntil(context: Context): Long = prefs(context).getLong("pending_until", 0L)

    fun clearPending(context: Context) {
        prefs(context).edit().remove("pending").remove("pending_until").commit()
    }

    // Cloak: the new secret, held until pairing is confirmed
    private const val MAIN_PENDING_MS = 5 * 60 * 1000L

    fun setMainPending(context: Context, secret: String) {
        prefs(context).edit()
            .putString("main_pending", secret)
            .putLong("main_pending_at", System.currentTimeMillis())
            .commit()
    }

    fun mainPending(context: Context): String? {
        val p = prefs(context)
        val at = p.getLong("main_pending_at", 0L)
        val secret = p.getString("main_pending", null)
        if (secret != null && System.currentTimeMillis() - at > MAIN_PENDING_MS) {
            clearMainPending(context)
            return null
        }
        return secret
    }

    fun clearMainPending(context: Context) {
        prefs(context).edit().remove("main_pending").remove("main_pending_at").commit()
    }
}