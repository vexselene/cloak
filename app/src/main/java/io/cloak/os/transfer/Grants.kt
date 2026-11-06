package io.cloak.os.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri

object Grants {
    private const val RO = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private const val RW = RO or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private const val FORWARDED_MAX_AGE_MS = 6L * 3_600_000L

    /** Keeps access across restarts. With write = true it falls back to read-only if that is all there is. */
    fun persist(context: Context, uri: Uri, write: Boolean): Boolean {
        val resolver = context.contentResolver
        if (write && runCatching { resolver.takePersistableUriPermission(uri, RW) }.isSuccess) return true
        return runCatching { resolver.takePersistableUriPermission(uri, RO) }.isSuccess
    }

    fun release(context: Context, uri: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, RW) }
    }

    /**
     * Grants that arrived from the other profile carry a user id in the authority. They are not
     * tracked anywhere else, so old ones are dropped. Android caps how many an app may keep.
     */
    fun pruneForwarded(context: Context) {
        if (TransferHub.isRunning) return
        val cutoff = System.currentTimeMillis() - FORWARDED_MAX_AGE_MS
        context.contentResolver.persistedUriPermissions
            .filter { it.uri.authority?.contains('@') == true && it.persistedTime < cutoff }
            .forEach { release(context, it.uri) }
    }
}