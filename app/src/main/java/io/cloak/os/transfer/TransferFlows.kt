package io.cloak.os.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.cloak.os.bridge.Commands

object TransferFlows {
    const val MAX_SOURCES = 200

    /** Keeps access, measures every source and records the transfer. Blocking, call off the main thread. */
    fun prepare(context: Context, sources: List<Uri>): LedgerEntry {
        sources.forEach { Grants.persist(context, it, write = true) }
        try {
            val snaps = sources.map { SourceScanner.snapshot(context, it) }
            val entry = LedgerEntry(TransferId.generate(), System.currentTimeMillis(), sources, snaps)
            MoveLedger.prune(context)
            if (!MoveLedger.save(context, entry)) throw java.io.IOException("could not save the ledger")
            return entry
        } catch (t: Throwable) {
            sources.forEach { Grants.release(context, it) }
            throw t
        }
    }

    /** The signed command that hands the picked sources to the space. */
    fun sendIntent(context: Context, entry: LedgerEntry): Intent {
        val intent = Commands.build(context, Commands.TRANSFER, entry.id, entry.sources)
        var flags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        if (entry.sources.any { DocTree.isTree(it) }) flags = flags or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        return intent.addFlags(flags)
    }
}