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
import android.provider.DocumentsContract

object TransferProbeUtil {
    const val TAG = "SecondSpace/Transfer"
    const val TEST_FOLDER = "cloak-probe-folder"
    const val PROBE_DOC_PREFIX = "cloak-probe-doc-"
    private const val MAX_WALK = 3000
    private const val MAX_READ = 512L shl 20
    private val PAYLOAD = "cloak transfer probe 0123456789".repeat(32).toByteArray()

    class Child(val id: String, val name: String, val mime: String, val size: Long) {
        val isDir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    fun isPureTree(uri: Uri): Boolean =
        uri.pathSegments.size == 2 && uri.pathSegments[0] == "tree"

    private fun hasGrant(context: Context, uri: Uri, flag: Int) =
        context.checkUriPermission(uri, Process.myPid(), Process.myUid(), flag) ==
                PackageManager.PERMISSION_GRANTED

    private fun step(say: (String) -> Unit, name: String, block: () -> String) {
        say("  $name: " + runCatching(block).getOrElse { "FAILED ${it.javaClass.simpleName}: ${it.message}" })
    }

    private fun children(context: Context, tree: Uri, parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val out = ArrayList<Child>()
        context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    Child(c.getString(0), c.getString(1) ?: "?", c.getString(2) ?: "",
                        if (c.isNull(3)) -1L else c.getLong(3))
                )
            }
        }
        return out
    }

    /** Exercises a folder grant received from the other profile. Only touches its own probe files. */
    fun testTree(context: Context, tree: Uri, say: (String) -> Unit) {
        val resolver = context.contentResolver
        say("tree: $tree")
        say(
            "  grants: read=${hasGrant(context, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)} " +
                    "write=${hasGrant(context, tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}"
        )
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val all = ArrayList<Child>()
        step(say, "walk") {
            val t0 = SystemClock.elapsedRealtime()
            val stack = ArrayDeque<String>()
            stack.add(rootId)
            while (stack.isNotEmpty() && all.size < MAX_WALK) {
                for (c in children(context, tree, stack.removeLast())) {
                    all.add(c)
                    if (c.isDir) stack.add(c.id)
                }
            }
            val ms = SystemClock.elapsedRealtime() - t0
            "${all.count { !it.isDir }} files, ${all.count { it.isDir }} folders, " +
                    "${all.sumOf { maxOf(it.size, 0L) }} bytes listed in $ms ms" +
                    if (all.size >= MAX_WALK) " (stopped at $MAX_WALK entries)" else ""
        }
        step(say, "read all") {
            val t0 = SystemClock.elapsedRealtime()
            val buf = ByteArray(64 * 1024)
            var n = 0
            var total = 0L
            for (f in all.filter { !it.isDir }) {
                if (total >= MAX_READ) break
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, f.id)
                resolver.openInputStream(uri)?.use { input ->
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        total += r
                    }
                }
                n++
            }
            val ms = SystemClock.elapsedRealtime() - t0
            "$n files, $total bytes in $ms ms" + (if (n > 0) " (${ms / n} ms per file)" else "") +
                    (if (total >= MAX_READ) ", stopped at the size cap" else "")
        }
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        var doc: Uri? = null
        step(say, "create") {
            doc = DocumentsContract.createDocument(
                resolver, root, "text/plain", "cloak-probe-${System.currentTimeMillis()}.txt"
            )
            doc?.toString() ?: "null returned"
        }
        doc?.let { d ->
            step(say, "write") {
                resolver.openOutputStream(d, "w")!!.use { it.write(PAYLOAD) }
                "${PAYLOAD.size} bytes"
            }
            step(say, "read back") {
                val back = resolver.openInputStream(d)!!.use { it.readBytes() }
                if (back.contentEquals(PAYLOAD)) "matches" else "MISMATCH, got ${back.size} bytes"
            }
            step(say, "delete") {
                if (DocumentsContract.deleteDocument(resolver, d)) "deleted" else "refused"
            }
        }
        step(say, "delete $TEST_FOLDER") {
            val folder = all.firstOrNull { it.isDir && it.name == TEST_FOLDER }
            when {
                folder == null -> "none present"
                DocumentsContract.deleteDocument(
                    resolver, DocumentsContract.buildDocumentUriUsingTree(tree, folder.id)
                ) -> "deleted with its contents"
                else -> "refused"
            }
        }
    }

    /** Write, read back and delete a single forwarded file. Only runs on files this probe created. */
    fun testDoc(context: Context, uri: Uri, say: (String) -> Unit) {
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        if (name == null || !name.startsWith(PROBE_DOC_PREFIX)) {
            say("  not a probe file, skipping write and delete")
            return
        }
        step(say, "write") {
            resolver.openOutputStream(uri, "w")!!.use { it.write(PAYLOAD) }
            "${PAYLOAD.size} bytes"
        }
        step(say, "read back") {
            val back = resolver.openInputStream(uri)!!.use { it.readBytes() }
            if (back.contentEquals(PAYLOAD)) "matches" else "MISMATCH, got ${back.size} bytes"
        }
        step(say, "delete") {
            if (DocumentsContract.deleteDocument(resolver, uri)) "deleted" else "refused"
        }
    }

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