package io.cloak.os.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import io.cloak.os.bridge.LockSettings
import io.cloak.os.common.Logx

object SessionManager {
    private const val TAG = "SecondSpace/Session"
    private const val PREFS = "session"
    private const val KEY_OPEN = "open"
    private const val KEY_OPENED_AT = "opened_at"
    private const val KEY_HIDDEN = "hidden"
    private const val FRESH_MS = 10_000L

    /** Never hidden, so the profile keeps a way to be inspected. */
    private val NEVER_HIDE = setOf("com.android.settings")

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)

    /** Lock command, notification action and screen-off all land here. */
    fun lock(context: Context, lockDevice: Boolean = true): Result<Unit> = runCatching {
        LockService.stop(context)
        if (LockSettings.load(context).hideApps) hideAll(context)
        setOpen(context, false)
        if (lockDevice) PolicyManager.lockNow(context).getOrThrow()
    }.onFailure { Logx.e(TAG, "lock failed", it) }

    /** Sent by Cloak whenever it opens with the profile unlocked. */
    fun onAppOpened(context: Context) {
        if (!LockSettings.load(context).hideApps) return
        val p = prefs(context)
        val open = p.getBoolean(KEY_OPEN, false)
        val fresh = System.currentTimeMillis() - p.getLong(KEY_OPENED_AT, 0L) < FRESH_MS
        when {
            !open -> openSession(context)
            LockService.running || fresh -> Unit
            else -> {
                Logx.w(TAG, "stale session, locking")
                lock(context)
            }
        }
    }

    fun onModeChanged(context: Context, enabled: Boolean) {
        if (enabled) {
            openSession(context)
        } else {
            LockService.stop(context)
            unhideRecorded(context)
            setOpen(context, false)
        }
    }

    /** Boot or app update: the listener is gone, so hide anything left visible. No lockNow. */
    fun reconcile(context: Context) {
        if (!LockSettings.load(context).hideApps) return
        if (prefs(context).getBoolean(KEY_OPEN, false) && !LockService.running) {
            Logx.d(TAG, "reconcile: hiding apps left open")
            hideAll(context)
            setOpen(context, false)
        }
    }

    private fun openSession(context: Context) {
        unhideRecorded(context)
        prefs(context).edit()
            .putBoolean(KEY_OPEN, true)
            .putLong(KEY_OPENED_AT, System.currentTimeMillis())
            .commit()
        LockService.start(context)
    }

    private fun setOpen(context: Context, open: Boolean) {
        prefs(context).edit().putBoolean(KEY_OPEN, open).commit()
    }

    private fun launchable(context: Context): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName && it !in NEVER_HIDE }
    }

    private fun hideAll(context: Context) {
        val dpm = dpm(context)
        val admin = SpaceAdminReceiver.component(context)
        val p = prefs(context)
        val targets = launchable(context)
        // Record first. Once an app is hidden it no longer shows up in any listing.
        val record = p.getStringSet(KEY_HIDDEN, emptySet()).orEmpty() + targets
        p.edit().putStringSet(KEY_HIDDEN, record).commit()
        val hidden = targets.count { pkg ->
            runCatching {
                dpm.setApplicationHidden(admin, pkg, true)
                dpm.isApplicationHidden(admin, pkg)
            }.getOrDefault(false)
        }
        Logx.d(TAG, "hid $hidden of ${targets.size} apps")
    }

    private fun unhideRecorded(context: Context) {
        val dpm = dpm(context)
        val admin = SpaceAdminReceiver.component(context)
        val p = prefs(context)
        val record = p.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
        // Judge success by the resulting state, not the return value. Failures stay recorded.
        val failed = record.filterNot { pkg ->
            runCatching {
                dpm.setApplicationHidden(admin, pkg, false)
                !dpm.isApplicationHidden(admin, pkg)
            }.getOrDefault(false)
        }.toSet()
        p.edit().putStringSet(KEY_HIDDEN, failed).commit()
        Logx.d(TAG, "unhid ${record.size - failed.size} of ${record.size} apps")
    }
}