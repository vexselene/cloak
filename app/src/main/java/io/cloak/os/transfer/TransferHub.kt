package io.cloak.os.transfer

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TransferHub {
    private val _state = MutableStateFlow<TransferState?>(null)
    val state: StateFlow<TransferState?> = _state.asStateFlow()

    @Volatile private var pending: TransferJob? = null
    @Volatile private var engine: TransferEngine? = null

    val isRunning: Boolean get() = _state.value?.running == true

    @Synchronized
    fun start(context: Context, job: TransferJob): Boolean {
        if (isRunning) return false
        val app = context.applicationContext
        pending = job
        _state.value = TransferState(Phase.PREPARING)
        val started = runCatching {
            app.startForegroundService(Intent(app, TransferService::class.java))
        }.isSuccess
        if (!started) {
            pending = null
            _state.value = TransferState(Phase.FAILED)
        }
        return started
    }

    internal fun takePending(): TransferJob? {
        val job = pending
        pending = null
        return job
    }

    internal fun attach(e: TransferEngine?) {
        engine = e
    }

    internal fun publish(s: TransferState) {
        _state.value = s
    }

    fun cancel() {
        engine?.cancelled = true
    }

    fun clear() {
        if (!isRunning) _state.value = null
    }
}