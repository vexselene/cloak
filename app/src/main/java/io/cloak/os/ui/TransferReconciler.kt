package io.cloak.os.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.cloak.os.bridge.Commands
import io.cloak.os.common.Logx
import io.cloak.os.transfer.MoveLedger
import io.cloak.os.transfer.Op
import io.cloak.os.transfer.ReportStore
import io.cloak.os.transfer.TransferHub
import io.cloak.os.transfer.TransferReport
import io.cloak.os.transfer.TransferWork

private const val TAG = "SecondSpace/Reconcile"
private const val POLL_WINDOW_MS = 12L * 3_600_000L
private const val MIN_GAP_MS = 8_000L

private sealed class Task {
    class Poll(val id: String) : Task()
    class Deliver(val report: TransferReport) : Task()
}

class TransferReconciler internal constructor(private val context: Context) {
    internal var launch: (Intent) -> Unit = {}
    private val queue = ArrayDeque<Task>()
    private var current: Task? = null
    private var lastRun = 0L

    /** Called when Cloak comes forward. Does nothing, and flashes nothing, unless something is waiting. */
    fun trigger() {
        if (SystemClock.elapsedRealtime() - lastRun < MIN_GAP_MS) return
        run()
    }

    fun checkNow() = run()

    private fun run() {
        if (current != null) return
        lastRun = SystemClock.elapsedRealtime()
        MoveLedger.prune(context)
        val cutoff = System.currentTimeMillis() - POLL_WINDOW_MS
        MoveLedger.all(context).filter { it.createdAt >= cutoff }.forEach { queue.add(Task.Poll(it.id)) }
        ReportStore.undelivered(context).forEach { queue.add(Task.Deliver(it)) }
        next()
    }

    private fun next() {
        val task = queue.removeFirstOrNull()
        if (task == null) {
            current = null
            return
        }
        current = task
        val sent = runCatching {
            val intent = when (task) {
                is Task.Poll -> Commands.build(context, Commands.TRANSFER_REPORT, task.id)
                is Task.Deliver -> Commands.build(context, Commands.TRANSFER_DONE, task.report.encode())
            }
            launch(intent)
        }
        if (sent.isFailure) {
            Logx.e(TAG, "could not ask the space", sent.exceptionOrNull())
            current = null
            queue.clear()
        }
    }

    internal fun onResult(result: ActivityResult) {
        val task = current ?: return
        current = null
        when (task) {
            is Task.Poll -> if (result.resultCode == Activity.RESULT_OK) {
                TransferReport.decode(result.data?.getStringExtra(Commands.EXTRA_REPORT))?.let { settle(it) }
            }
            is Task.Deliver -> if (result.resultCode == Activity.RESULT_OK) {
                ReportStore.markDelivered(context, task.report.id)
            }
        }
        next()
    }

    private fun settle(report: TransferReport) {
        val entry = MoveLedger.get(context, report.id) ?: return
        if (report.op != Op.MOVE || !report.complete) {
            // Copy only, or the transfer did not finish: nothing may be deleted.
            MoveLedger.remove(context, entry.id)
            return
        }
        Logx.d(TAG, "settling a move: ${report.results.size} verified source(s)")
        TransferHub.start(context, TransferWork.Settle(entry, report))
    }
}

@Composable
fun rememberTransferReconciler(): TransferReconciler {
    val context = LocalContext.current.applicationContext
    val reconciler = remember { TransferReconciler(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        reconciler.onResult(it)
    }
    reconciler.launch = { launcher.launch(it) }
    return reconciler
}