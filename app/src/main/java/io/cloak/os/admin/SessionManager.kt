package io.cloak.os.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import io.cloak.os.bridge.LockSettings
import io.cloak.os.common.Logx
import android.os.Build

object SessionManager {
    private const val TAG = "SecondSpace/Session"
    private const val PREFS = "session"
    private const val KEY_OPEN = "open"
    private const val KEY_OPENED_AT = "opened_at"
    private const val KEY_HIDDEN = "hidden"
    private const val KEY_KNOWN = "known"
    private const val KEY_SHUTDOWN_NOTE = "shutdown_note"
    private const val FRESH_MS = 10_000L

    /** Never hidden, so the profile keeps a way to be inspected. */
    private val NEVER_HIDE = setOf("com.android.settings")

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)

    /** Lock command, notification action and screen-off all land here. */
    fun lock(context: Context, lockDevice: Boolean = true): Result<Unit> = runCatching {
        LockService.stop(context)
        if (LockSettings.load(context).hideApps) {
            val live = launchable(context)
            if (live.isNotEmpty()) saveKnown(context, live)
            setOpen(context, !hide(context, live))
        } else {
            setOpen(context, false)
        }
        if (lockDevice) PolicyManager.lockNow(context).getOrThrow()
    }.onFailure { Logx.e(TAG, "lock failed", it) }

    /** Sent by Cloak whenever it comes to the foreground with the profile unlocked. */
    fun onAppOpened(context: Context, refresh: Boolean = false) {
        if (!LockSettings.load(context).hideApps) return
        val p = prefs(context)
        val open = p.getBoolean(KEY_OPEN, false)
        val fresh = System.currentTimeMillis() - p.getLong(KEY_OPENED_AT, 0L) < FRESH_MS
        when {
            !open -> openSession(context)
            LockService.running -> if (refresh) saveKnown(context, launchable(context))
            fresh -> Unit
            else -> {
                Logx.w(TAG, "stale session, locking")
                lock(context)
            }
        }
    }

    fun onSettingsChanged(context: Context, old: LockSettings, new: LockSettings) {
        when {
            old.hideApps != new.hideApps -> onModeChanged(context, new.hideApps)
            old.hideOnShutdown != new.hideOnShutdown && LockService.running -> LockService.refresh(context)
        }
    }

    private fun onModeChanged(context: Context, enabled: Boolean) {
        if (enabled) {
            openSession(context)
        } else {
            LockService.stop(context)
            unhideRecorded(context)
            recoverOrphans(context)
            setOpen(context, false)
        }
    }

    /**
     * Boot or app update: the listener is gone, so hide anything left visible. Hides by saved
     * package names, because the app list is unreliable before the first unlock. Never evicts
     * the key. Retried on every trigger until it succeeds.
     */
    fun reconcile(context: Context) {
        if (!LockSettings.load(context).hideApps) return
        val p = prefs(context)
        if (!p.getBoolean(KEY_OPEN, false) || LockService.running) return
        val known = p.getStringSet(KEY_KNOWN, null)
        val live = launchable(context)
        if (known == null && live.isEmpty()) {
            Logx.w(TAG, "reconcile: no app list yet, will retry")
            return
        }
        val targets = known.orEmpty() + live
        Logx.d(TAG, "reconcile: ${targets.size} apps to hide")
        if (hide(context, targets)) setOpen(context, false)
    }

    fun reportShutdown(context: Context) {
        val p = prefs(context)
        p.getString(KEY_SHUTDOWN_NOTE, null)?.let {
            Logx.d(TAG, "previous shutdown: $it")
            p.edit().remove(KEY_SHUTDOWN_NOTE).commit()
        }
    }

    /** Best effort, called by the listener on ACTION_SHUTDOWN. Hides only, no key eviction. */
    fun onShutdown(context: Context) {
        if (!LockSettings.load(context).shutdownActive) return
        val t0 = SystemClock.elapsedRealtime()
        val live = launchable(context)
        val complete = hide(context, live)
        if (complete) setOpen(context, false)
        val note = "hid ${live.size} apps, complete=$complete, ${SystemClock.elapsedRealtime() - t0} ms"
        prefs(context).edit().putString(KEY_SHUTDOWN_NOTE, note).commit()
    }

    private fun openSession(context: Context) {
        val unhidden = unhideRecorded(context)
        val p = prefs(context)
        p.edit()
            .putBoolean(KEY_OPEN, true)
            .putLong(KEY_OPENED_AT, System.currentTimeMillis())
            .commit()
        // Built once. After that it changes only on clone or uninstall, or at lock if it differs.
        if (!p.contains(KEY_KNOWN)) saveKnown(context, launchable(context) + unhidden)
        LockService.start(context)
    }

    private fun setOpen(context: Context, open: Boolean) {
        prefs(context).edit().putBoolean(KEY_OPEN, open).commit()
    }

    private fun saveKnown(context: Context, packages: Set<String>) {
        if (packages.isEmpty()) return
        val p = prefs(context)
        if (p.getStringSet(KEY_KNOWN, null) == packages) return
        p.edit().putStringSet(KEY_KNOWN, packages).commit()
    }

    private fun launchable(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val flags = PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE
        return context.packageManager.queryIntentActivities(intent, flags)
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName && it !in NEVER_HIDE }
            .toSet()
    }

    /** A package that no longer exists counts as handled. A hidden one still resolves with this flag. */
    @Suppress("DEPRECATION")
    private fun isGone(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        false
    }.getOrDefault(true)

    /** True only if every target ended up hidden (or gone). An empty list counts as done. */
    private fun hide(context: Context, targets: Collection<String>): Boolean {
        if (targets.isEmpty()) return true
        val dpm = dpm(context)
        val admin = SpaceAdminReceiver.component(context)
        val p = prefs(context)
        // Record first. Once an app is hidden it no longer appears in any listing.
        p.edit().putStringSet(KEY_HIDDEN, p.getStringSet(KEY_HIDDEN, emptySet()).orEmpty() + targets).commit()
        val done = targets.count { pkg ->
            runCatching {
                dpm.setApplicationHidden(admin, pkg, true)
                dpm.isApplicationHidden(admin, pkg)
            }.getOrDefault(false) || isGone(context, pkg)
        }
        Logx.d(TAG, "hid $done of ${targets.size} apps")
        return done == targets.size
    }

    /** Returns the packages that were successfully unhidden. Failures stay recorded. */
    private fun unhideRecorded(context: Context): Set<String> {
        val dpm = dpm(context)
        val admin = SpaceAdminReceiver.component(context)
        val p = prefs(context)
        val record = p.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
        val failed = record.filterNot { pkg ->
            runCatching {
                dpm.setApplicationHidden(admin, pkg, false)
                !dpm.isApplicationHidden(admin, pkg)
            }.getOrDefault(false)
        }.toSet()
        p.edit().putStringSet(KEY_HIDDEN, failed).commit()
        Logx.d(TAG, "unhid ${record.size - failed.size} of ${record.size} apps")
        return record - failed
    }

    /**
     * With hide mode off nothing should be hidden. If something is, the record of what was hidden was
     * lost (for example the space's data was cleared), so find it from the system's own state.
     */
    fun recoverOrphans(context: Context): Int {
        if (LockSettings.load(context).hideApps) return 0
        val dpm = dpm(context)
        val admin = SpaceAdminReceiver.component(context)
        val packages = installedPackages(context)
        var shown = 0
        for (pkg in packages) {
            if (pkg == context.packageName) continue
            val hidden = runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false)
            if (!hidden) continue
            val ok = runCatching {
                dpm.setApplicationHidden(admin, pkg, false)
                !dpm.isApplicationHidden(admin, pkg)
            }.getOrDefault(false)
            if (ok) shown++
        }
        if (shown > 0) prefs(context).edit().remove(KEY_HIDDEN).commit()
        Logx.d(TAG, "orphan scan: saw ${packages.size} packages, showed $shown")
        return shown
    }

    @Suppress("DEPRECATION")
    private fun installedPackages(context: Context): List<String> {
        val pm = context.packageManager
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or
                PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            pm.getInstalledApplications(flags)
        }
        return apps.map { it.packageName }
    }
}