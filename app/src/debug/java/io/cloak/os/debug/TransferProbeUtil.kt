package io.cloak.os.debug

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.OpenableColumns
import android.util.Log

object TransferProbeUtil {
    const val TAG = "SecondSpace/Transfer"

    fun where(context: Context): String {
        val managed = context.getSystemService(UserManager::class.java).isManagedProfile
        return "${if (managed) "SPACE (work profile)" else "PERSONAL"} ${Process.myUserHandle()}"
    }

    @Suppress("DEPRECATION")
    fun sharedUris(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        intent.clipData?.let { c ->
            for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { out.add(it) }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { out.add(it) }
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { out.addAll(it) }
        } else {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.add(it) }
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
        }
        return out.toList()
    }

    /** Describes a URI and reads it completely, reporting size, speed and the grants we hold. */
    fun inspect(context: Context, uri: Uri, slow: Boolean = false): String {
        val sb = StringBuilder()
        sb.appendLine("uri: $uri")
        sb.appendLine("  scheme=${uri.scheme} host=${uri.host} authority=${uri.authority}")
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (n >= 0) c.getString(n) else "?"
                    val size = if (s >= 0 && !c.isNull(s)) c.getLong(s) else -1L
                    sb.appendLine("  name=$name reportedSize=$size")
                }
            }
        }.onFailure { sb.appendLine("  query failed: ${it.javaClass.simpleName}") }
        sb.appendLine("  mime=${runCatching { context.contentResolver.getType(uri) }.getOrNull()}")

        fun granted(flag: Int) = context.checkUriPermission(
            uri, Process.myPid(), Process.myUid(), flag
        ) == PackageManager.PERMISSION_GRANTED
        sb.appendLine(
            "  grants: read=${granted(Intent.FLAG_GRANT_READ_URI_PERMISSION)} " +
                    "write=${granted(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}"
        )

        val t0 = SystemClock.elapsedRealtime()
        val total = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(64 * 1024)
                var sum = 0L
                var nextLog = 8L shl 20
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sum += n
                    if (slow) {
                        Thread.sleep(30)
                        if (sum >= nextLog) {
                            Log.d(TAG, "  progress ${sum shr 20} MB")
                            nextLog += 8L shl 20
                        }
                    }
                }
                sum
            }
        }
        val ms = SystemClock.elapsedRealtime() - t0
        total.onSuccess {
            if (it == null) {
                sb.appendLine("  read: no stream")
            } else {
                val mbs = if (ms > 0) it / 1048576.0 / (ms / 1000.0) else 0.0
                sb.appendLine("  read: $it bytes in $ms ms (${"%.1f".format(mbs)} MB/s)")
            }
        }.onFailure { sb.appendLine("  read FAILED: ${it.javaClass.simpleName}: ${it.message}") }
        return sb.toString()
    }
}