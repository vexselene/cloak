package io.cloak.os.admin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import io.cloak.os.R
import io.cloak.os.bridge.LockSettings
import io.cloak.os.common.Logx

class LockService : Service() {
    private var receiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOCK -> {
                SessionManager.lock(this)
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                if (receiver != null) register() else stopSelf()
                return START_NOT_STICKY
            }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.session_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        register()
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unregister()
        running = false
    }

    private fun unregister() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
    }

    private fun register() {
        unregister()
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Logx.d(TAG, "screen off, locking")
                        SessionManager.lock(this@LockService)
                    }
                    Intent.ACTION_SHUTDOWN -> {
                        Logx.d(TAG, "shutdown, hiding")
                        SessionManager.onShutdown(this@LockService)
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            if (LockSettings.load(this@LockService).shutdownActive) addAction(Intent.ACTION_SHUTDOWN)
        }
        ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    private fun buildNotification(): Notification {
        val lock = PendingIntent.getService(
            this, 0,
            Intent(this, LockService::class.java).setAction(ACTION_LOCK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_lock_lock),
            getString(R.string.session_notification_lock),
            lock
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.session_notification_title))
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(action)
            .build()
    }

    companion object {
        const val ACTION_LOCK = "io.cloak.os.action.LOCK_SESSION"
        const val ACTION_REFRESH = "io.cloak.os.action.REFRESH_SESSION"
        private const val TAG = "SecondSpace/LockService"
        private const val CHANNEL = "session"
        private const val NOTIFICATION_ID = 2

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LockService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LockService::class.java))
        }

        /** Re-registers the listener so a changed setting takes effect mid-session. */
        fun refresh(context: Context) {
            if (!running) return
            context.startService(Intent(context, LockService::class.java).setAction(ACTION_REFRESH))
        }
    }
}