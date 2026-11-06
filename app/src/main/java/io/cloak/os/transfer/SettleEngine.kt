package io.cloak.os.transfer

import android.content.Context
import android.os.SystemClock
import io.cloak.os.common.Logx

/** Removes originals after a verified move. Runs on one background thread. */
class SettleEngine(
    context: Context,
    private val entry: LedgerEntry,
    private val report: TransferReport,
    private val onState: (TransferState) -> Unit
) {
    @Volatile
    var cancelled = false

    private val appContext = context.applicationContext
    private var filesTotal = 0
    private var removed = 0
    private var kept = 0
    private var failedSources = 0
    private var lastPublish = 0L

    fun run(): TransferState {
        publish(Phase.PREPARING, force = true)
        val eligible = ArrayList<Int>()
        val results = report.results.distinctBy { it.index }
        for (r in results) {
            if (cancelled) return finish(Phase.CANCELLED)
            val snap = entry.snaps.getOrNull(r.index)
            val source = entry.sources.getOrNull(r.index)
            val same = snap != null && source != null && snap.bytes >= 0 &&
                    r.files == snap.files && r.bytes == snap.bytes
            if (!same) {
                kept++
                continue
            }
            // The source must still look exactly like it did when it was picked.
            val fresh = runCatching { SourceScanner.snapshot(appContext, source!!) }.getOrNull()
            if (fresh == snap) eligible.add(r.index) else kept++
        }
        kept += entry.sources.size - results.size
        filesTotal = eligible.sumOf { entry.snaps[it].files }
        publish(Phase.SETTLING, force = true)
        for (index in eligible) {
            if (cancelled) return finish(Phase.CANCELLED)
            val ok = runCatching {
                SourceScanner.delete(appContext, entry.sources[index], { cancelled }) {
                    removed++
                    publish(Phase.SETTLING, force = false)
                }
            }.onFailure { Logx.e(TAG, "delete failed", it) }.getOrDefault(false)
            if (!ok) failedSources++
        }
        return finish(if (cancelled) Phase.CANCELLED else Phase.DONE)
    }

    private fun snapshot(phase: Phase) = TransferState(
        phase = phase, filesDone = removed, filesTotal = filesTotal,
        copied = removed, skipped = kept, failed = failedSources,
        op = Op.MOVE, settle = true
    )

    private fun publish(phase: Phase, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublish < PUBLISH_MS) return
        lastPublish = now
        onState(snapshot(phase))
    }

    private fun finish(phase: Phase): TransferState = snapshot(phase)

    private companion object {
        const val TAG = "SecondSpace/Settle"
        const val PUBLISH_MS = 250L
    }
}