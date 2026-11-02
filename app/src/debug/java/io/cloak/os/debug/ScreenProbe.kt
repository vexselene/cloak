package io.cloak.os.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat

object ScreenProbe {
    private const val TAG = "SecondSpace/Probe"
    private const val PREFS = "probe"

    @Volatile private var receiver: BroadcastReceiver? = null

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun register(context: Context, mode: String) {
        val app = context.applicationContext
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = onScreenOff(app)
            }
            ContextCompat.registerReceiver(
                app, r, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiver = r
        }
        prefs(app).edit()
            .putString("mode", mode)
            .putLong("started_at", System.currentTimeMillis())
            .putInt("start_pid", Process.myPid())
            .putInt("off_count", 0)
            .putLong("last_off", 0L)
            .commit()
        Log.d(TAG, "registered mode=$mode pid=${Process.myPid()}")
    }

    @Synchronized
    fun unregister(context: Context) {
        val r = receiver ?: return
        runCatching { context.applicationContext.unregisterReceiver(r) }
        receiver = null
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, ProbeService::class.java))
        unregister(context)
        prefs(context).edit().putString("mode", "").commit()
    }

    private fun onScreenOff(app: Context) {
        val p = prefs(app)
        val count = p.getInt("off_count", 0) + 1
        p.edit().putInt("off_count", count).putLong("last_off", System.currentTimeMillis()).commit()
        Log.d(TAG, "SCREEN_OFF received, count=$count pid=${Process.myPid()}")
    }

    private fun ago(ms: Long): String =
        if (ms == 0L) "never" else "${(System.currentTimeMillis() - ms) / 60_000} min ago"

    fun status(context: Context): String {
        val p = prefs(context)
        val mode = p.getString("mode", "").orEmpty()
        if (mode.isEmpty()) return "Probe not running"
        val started = ago(p.getLong("started_at", 0L))
        val events = "screen-off events: ${p.getInt("off_count", 0)}, last ${ago(p.getLong("last_off", 0L))}"
        return if (receiver != null) {
            "$mode probe ALIVE (pid ${Process.myPid()}), started $started, $events"
        } else {
            "$mode probe is GONE. Started $started in pid ${p.getInt("start_pid", -1)}, " +
                    "now pid ${Process.myPid()}. $events"
        }
    }
}