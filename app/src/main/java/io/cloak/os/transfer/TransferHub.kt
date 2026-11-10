package io.cloak.os.transfer

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class TransferWork {
    class Copy(val job: TransferJob) : TransferWork()
    class Settle(val entry: LedgerEntry, val report: TransferReport) : TransferWork()
}

object TransferHub {
    private val _state = MutableStateFlow<TransferState?>(null)
    val state: StateFlow<TransferState?> = _state.asStateFlow()

    @Volatile private var pending: TransferWork? = null
    @Volatile private var canceller: (() -> Unit)? = null
    @Volatile private var answerer: ((ConflictAnswer) -> Unit)? = null

    val isRunning: Boolean get() = _state.value?.running == true

    @Synchronized
    fun start(context: Context, work: TransferWork): Boolean {
        if (isRunning) return false
        val app = context.applicationContext
        pending = work
        _state.value = TransferState(
            Phase.PREPARING,
            op = if (work is TransferWork.Copy) work.job.op else Op.MOVE,
            settle = work is TransferWork.Settle
        )
        val started = runCatching {
            app.startForegroundService(Intent(app, TransferService::class.java))
        }.isSuccess
        if (!started) {
            pending = null
            _state.value = TransferState(Phase.FAILED)
        }
        return started
    }

    internal fun takePending(): TransferWork? {
        val work = pending
        pending = null
        return work
    }

    /** Passing null also forgets the answer handler, so a stale engine can never be answered. */
    internal fun attach(cancel: (() -> Unit)?) {
        canceller = cancel
        if (cancel == null) answerer = null
    }

    internal fun attachAnswer(handler: ((ConflictAnswer) -> Unit)?) {
        answerer = handler
    }

    internal fun publish(s: TransferState) {
        _state.value = s
    }

    fun cancel() {
        canceller?.invoke()
    }

    /** Answers the clash the transfer is paused on. */
    fun answer(a: ConflictAnswer) {
        answerer?.invoke(a)
    }

    fun clear() {
        if (!isRunning) _state.value = null
    }
}