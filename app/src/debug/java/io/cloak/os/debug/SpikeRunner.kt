package io.cloak.os.debug

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import io.cloak.os.admin.SpaceAdminReceiver
import io.cloak.os.bridge.Commands

object SpikeRunner {
    private const val TAG = "SecondSpace/Spike"
    private const val PREFS = "spike"
    private const val KEY_HIDDEN = "hidden_set"

    /** Never hidden by "hide all", so the work copy keeps a way to be repaired. */
    private val NEVER_HIDE = setOf("com.android.settings")

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun run(context: Context, arg: String) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isProfileOwnerApp(context.packageName)) return
        val op = arg.substringBefore(':')
        val value = arg.substringAfter(':', "")
        val msg = runCatching { dispatch(context, dpm, op, value) }
            .getOrElse {
                Log.e(TAG, "spike $op failed", it)
                "$op failed: ${it.javaClass.simpleName}: ${it.message}"
            }
        Log.d(TAG, msg)
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    private fun dispatch(context: Context, dpm: DevicePolicyManager, op: String, value: String): String {
        val admin = SpaceAdminReceiver.component(context)
        return when (op) {
            "hide" -> single(context, dpm, admin, true, value)
            "unhide" -> single(context, dpm, admin, false, value)
            "hideall" -> hideAll(context, dpm, admin)
            "unhideall" -> unhideAll(context, dpm, admin)
            "status" -> status(context, dpm, admin)
            "probe_fgs" -> startForegroundProbe(context, dpm, admin)
            "probe_plain" -> {
                ScreenProbe.register(context, "plain")
                "Plain probe started in pid ${Process.myPid()}"
            }
            "probe_stop" -> {
                ScreenProbe.stop(context)
                "Probe stopped"
            }
            "probe_status" -> ScreenProbe.status(context)
            "battery" -> openBattery(context)
            else -> "unknown spike op: $op"
        }
    }

    // ---- Spike 1: hide / unhide ----

    private fun single(
        context: Context, dpm: DevicePolicyManager, admin: ComponentName, hide: Boolean, pkg: String
    ): String {
        if (!Commands.isValidPackage(pkg) || pkg == context.packageName) return "bad package: $pkg"
        val t0 = SystemClock.elapsedRealtime()
        val ok = dpm.setApplicationHidden(admin, pkg, hide)
        val ms = SystemClock.elapsedRealtime() - t0
        val hiddenNow = dpm.isApplicationHidden(admin, pkg)
        return "${if (hide) "hide" else "unhide"} ok=$ok hidden=$hiddenNow in $ms ms"
    }

    private fun launchable(context: Context): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName && it !in NEVER_HIDE }
    }

    private fun hideAll(context: Context, dpm: DevicePolicyManager, admin: ComponentName): String {
        val p = prefs(context)
        val pkgs = launchable(context)
        // Record first, merged with any earlier record, so nothing can be lost.
        val recorded = p.getStringSet(KEY_HIDDEN, emptySet()).orEmpty() + pkgs
        p.edit().putStringSet(KEY_HIDDEN, recorded).commit()
        val t0 = SystemClock.elapsedRealtime()
        val ok = pkgs.count { runCatching { dpm.setApplicationHidden(admin, it, true) }.getOrDefault(false) }
        val ms = SystemClock.elapsedRealtime() - t0
        return "hid $ok of ${pkgs.size} apps in $ms ms"
    }

    private fun unhideAll(context: Context, dpm: DevicePolicyManager, admin: ComponentName): String {
        val p = prefs(context)
        val recorded = p.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
        val t0 = SystemClock.elapsedRealtime()
        val done = recorded.filter {
            runCatching { dpm.setApplicationHidden(admin, it, false) }.getOrDefault(false)
        }
        val ms = SystemClock.elapsedRealtime() - t0
        p.edit().putStringSet(KEY_HIDDEN, recorded - done.toSet()).commit()
        return "unhid ${done.size} of ${recorded.size} apps in $ms ms"
    }

    private fun status(context: Context, dpm: DevicePolicyManager, admin: ComponentName): String {
        val recorded = prefs(context).getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
        val hidden = recorded.count { runCatching { dpm.isApplicationHidden(admin, it) }.getOrDefault(false) }
        return "recorded ${recorded.size}, hidden now $hidden"
    }

    // ---- Spike 2: screen-off listener ----

    private fun startForegroundProbe(context: Context, dpm: DevicePolicyManager, admin: ComponentName): String {
        runCatching {
            dpm.setPermissionGrantState(
                admin, context.packageName, Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        }.onFailure { Log.w(TAG, "could not grant notification permission", it) }
        context.startForegroundService(Intent(context, ProbeService::class.java))
        return "Foreground probe starting"
    }

    private fun openBattery(context: Context): String {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        return runCatching {
            context.startActivity(request)
            "Opened battery exemption dialog"
        }.getOrElse {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                "Opened battery optimization list"
            }.getOrElse { "Could not open battery settings" }
        }
    }
}