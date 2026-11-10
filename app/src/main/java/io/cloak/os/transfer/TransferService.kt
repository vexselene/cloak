package io.cloak.os.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import io.cloak.os.MainActivity
import io.cloak.os.R
import io.cloak.os.common.Logx
import io.cloak.os.common.Role
import io.cloak.os.ui.ShareReceiverActivity

class TransferService : Service() {
    private var worker: Thread? = null
    @Volatile private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            TransferHub.cancel()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.transfer_notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        // Must be called promptly after startForegroundService, even if there turns out to be no job.
        startForeground(
            NOTIFICATION_ID,
            notification(TransferHub.state.value ?: TransferState(Phase.PREPARING)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val next = TransferHub.takePending()
        if (next == null || worker?.isAlive == true) {
            if (worker?.isAlive != true) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        worker = Thread { runWork(next) }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun runWork(work: TransferWork) {
        val lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cloak:transfer")
            .apply { setReferenceCounted(false) }
        lock.acquire(WAKE_MS)
        val result = try {
            when (work) {
                is TransferWork.Copy -> runCopy(work.job)
                is TransferWork.Settle -> runSettle(work)
            }
        } catch (t: Throwable) {
            Logx.e(TAG, "transfer crashed", t)
            TransferState(Phase.FAILED)
        }
        TransferHub.publish(result)
        if (lock.isHeld) lock.release()
        TransferHub.attach(null)
        Grants.pruneForwarded(applicationContext)
        stopForeground(STOP_FOREGROUND_DETACH)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(result))
        stopSelf()
    }

    private fun runCopy(job: TransferJob): TransferState {
        val engine = TransferEngine(applicationContext, job) { s ->
            TransferHub.publish(s)
            notifyProgress(s)
        }
        TransferHub.attach { engine.cancelled = true }
        TransferHub.attachAnswer(engine::answer)
        val result = engine.run()
        // Saved before the final state is published, so a poll right after always finds it.
        engine.report?.let { ReportStore.save(this, it, deliver = Role.detect(this) == Role.MAIN) }
        job.releaseAfter.forEach { Grants.release(this, it) }
        return result
    }

    private fun runSettle(work: TransferWork.Settle): TransferState {
        val engine = SettleEngine(applicationContext, work.entry, work.report) { s ->
            TransferHub.publish(s)
            notifyProgress(s)
        }
        TransferHub.attach { engine.cancelled = true }
        val result = engine.run()
        MoveLedger.remove(applicationContext, work.entry.id)
        return result
    }

    private fun notifyProgress(s: TransferState) {
        val now = SystemClock.elapsedRealtime()
        if (s.running && s.conflict == null && now - lastNotify < NOTIFY_MS) return
        lastNotify = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
    }

    private fun notification(s: TransferState): Notification {
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        val conflict = s.conflict
        if (s.running) {
            b.setContentTitle(getString(transferTitle(s)))
                .setContentText(
                    if (conflict != null) getString(R.string.transfer_notif_conflict, conflict.name)
                    else s.current.ifEmpty { getString(transferTitle(s)) }
                )
                .setOngoing(true)
                .addAction(cancelAction())
            if (conflict != null) {
                b.setContentIntent(openIntent())
                b.setProgress(0, 0, false)
            } else {
                val f = s.fraction
                if (f != null) b.setProgress(100, (f * 100).toInt(), false) else b.setProgress(0, 0, true)
            }
        } else {
            val text = when {
                s.needsSettle -> getString(R.string.transfer_move_pending)
                s.settle -> getString(R.string.transfer_settle_summary, s.copied, s.skipped, s.failed)
                else -> getString(R.string.transfer_summary, s.copied, s.skipped, s.failed)
            }
            b.setContentTitle(getString(transferTitle(s))).setContentText(text).setAutoCancel(true)
            if (Role.detect(this) == Role.MAIN) b.setContentIntent(openIntent())
        }
        return b.build()
    }

    /** Opens Cloak in the personal profile, or the receiver screen inside the space. */
    private fun openIntent(): PendingIntent {
        val target = if (Role.detect(this) == Role.MAIN) MainActivity::class.java
        else ShareReceiverActivity::class.java
        return PendingIntent.getActivity(
            this, 1,
            Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun cancelAction(): Notification.Action {
        val pi = PendingIntent.getService(
            this, 0,
            Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            getString(R.string.cancel),
            pi
        ).build()
    }

    private companion object {
        const val TAG = "SecondSpace/TransferService"
        const val ACTION_CANCEL = "io.cloak.os.action.CANCEL_TRANSFER"
        const val CHANNEL = "transfer"
        const val NOTIFICATION_ID = 3
        const val NOTIFY_MS = 500L
        const val WAKE_MS = 2 * 60 * 60 * 1000L
    }
}