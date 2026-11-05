package io.cloak.os.debug

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import io.cloak.os.bridge.ApkExporter
import java.io.File
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

class TransferProbeActivity : ComponentActivity() {
    private lateinit var out: TextView
    private val payload = "cloak transfer probe 0123456789".repeat(32).toByteArray()

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) treeTest(uri) else say("folder picker cancelled")
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Thread { say(TransferProbeUtil.inspect(this, uri)) }.start()
        else say("file picker cancelled")
    }
    private val createFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) createTest(uri) else say("create cancelled")
    }
    private val pickForTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) sendGrant(uri, true) else say("cancelled")
    }
    private val pickForFile = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) shareChildFile(uri) else say("cancelled")
    }
    private val pickForFill = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) fillAndShare(uri) else say("cancelled")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun button(label: String, action: () -> Unit) =
            column.addView(Button(this).apply { text = label; setOnClickListener { action() } })

        column.addView(TextView(this).apply { text = "Running in: ${TransferProbeUtil.where(this@TransferProbeActivity)}" })
        column.addView(TextView(this).apply { text = handlers(); textSize = 12f })
        column.addView(TextView(this).apply {
            text = "Files in this copy's cache/bridge: ${bridgeSize() / 1048576} MB"
            textSize = 12f
        })
        button("Delete probe files (this copy)") { clearBridge() }
        column.addView(TextView(this).apply { text = candidates(); textSize = 12f })
        column.addView(TextView(this).apply { text = forwarders(); textSize = 12f })
        button("Share 1 file (1 MB)") { shareTest(1, 1L shl 20) }
        button("Share 3 files (1 MB each)") { shareTest(3, 1L shl 20) }
        button("Share 1 large file (200 MB)") { shareTest(1, 200L shl 20) }
        button("Share a folder grant (pick any folder)") { launchSafe("folder picker") { pickForTree.launch(null) } }
        button("Share one file grant (pick a folder, a probe file is made in it)") {
            launchSafe("folder picker") { pickForFile.launch(null) }
        }
        button("Fill a folder with 350 files, then share it (pick an EMPTY folder)") {
            launchSafe("folder picker") { pickForFill.launch(null) }
        }
        button("Pick folder (list, create, write, read, delete)") { launchSafe("folder picker") { pickTree.launch(null) } }
        button("Pick file (read test)") { launchSafe("file picker") { pickFile.launch(arrayOf("*/*")) } }
        button("Create file (write test)") { launchSafe("create file") { createFile.launch("cloak-probe.txt") } }
        button("Clear log") { out.text = "" }
        column.addView(out)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun handlers(): String {
        val pm = packageManager
        val items = listOf(
            "folder picker" to Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
            "file picker" to Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            "create file" to Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            "get content" to Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        )
        return "Handlers here:\n" + items.joinToString("\n") { (label, intent) ->
            val found = pm.queryIntentActivities(intent, 0)
                .map { "${it.activityInfo.packageName}/${it.activityInfo.name.substringAfterLast('.')}" }
                .distinct()
            "  $label: ${if (found.isEmpty()) "NONE" else found.joinToString()}"
        }
    }

    private fun say(msg: String) {
        Log.d(TransferProbeUtil.TAG, msg)
        runOnUiThread { out.append(msg + "\n") }
    }

    private fun step(name: String, block: () -> String) {
        val result = runCatching(block)
        say("  $name: " + result.getOrElse { "FAILED ${it.javaClass.simpleName}: ${it.message}" })
    }

    // ---- Spike 4: sending test files through the share sheet ----

    private fun makeFiles(count: Int, bytes: Long): List<Uri> {
        val dir = File(cacheDir, "bridge").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("probe-") }?.forEach { it.delete() }
        val chunk = ByteArray(1 shl 16) { (it % 251).toByte() }
        return (1..count).map { i ->
            val file = File(dir, "probe-${System.currentTimeMillis()}-$i.bin")
            file.outputStream().use { stream ->
                var left = bytes
                while (left > 0) {
                    val n = minOf(left, chunk.size.toLong()).toInt()
                    stream.write(chunk, 0, n)
                    left -= n
                }
            }
            FileProvider.getUriForFile(this, ApkExporter.AUTHORITY, file)
        }
    }

    private fun shareTest(count: Int, bytes: Long) {
        say("creating $count file(s) of $bytes bytes...")
        Thread {
            val uris = runCatching { makeFiles(count, bytes) }
            uris.onFailure { say("  create FAILED: ${it.javaClass.simpleName}: ${it.message}") }
            val list = uris.getOrNull() ?: return@Thread
            runOnUiThread {
                val send = Intent(if (list.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
                    .setType("application/octet-stream")
                if (list.size == 1) send.putExtra(Intent.EXTRA_STREAM, list[0])
                else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(list))
                val clip = ClipData.newRawUri("probe", list[0])
                list.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                send.clipData = clip
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                say("opening share sheet")
                runCatching { startActivity(Intent.createChooser(send, "Share probe files")) }
                    .onFailure { say("  share FAILED: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }.start()
    }

    // ---- Spike 5: pickers ----

    private fun treeTest(tree: Uri) {
        say("folder: $tree")
        Thread {
            runCatching {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }.onFailure { say("  persist grant: ${it.javaClass.simpleName}") }
                val resolver = contentResolver
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
                step("list") {
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
                    val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    resolver.query(children, columns, null, null, null)?.use { "${it.count} entries" }
                        ?: "no cursor"
                }
                var doc: Uri? = null
                step("create") {
                    doc = DocumentsContract.createDocument(
                        resolver, root, "text/plain", "cloak-probe-${System.currentTimeMillis()}.txt"
                    )
                    doc?.toString() ?: "null returned"
                }
                val d = doc
                if (d != null) {
                    step("write") {
                        resolver.openOutputStream(d, "w")!!.use { it.write(payload) }
                        "${payload.size} bytes"
                    }
                    step("read back") {
                        val back = resolver.openInputStream(d)!!.use { it.readBytes() }
                        if (back.contentEquals(payload)) "matches" else "MISMATCH, got ${back.size} bytes"
                    }
                    step("delete") {
                        if (DocumentsContract.deleteDocument(resolver, d)) "deleted" else "refused"
                    }
                }
                say("folder test done")
            }.onFailure { say("folder test aborted: ${it.javaClass.simpleName}: ${it.message}") }
        }.start()
    }

    private fun createTest(uri: Uri) {
        Thread {
            runCatching {
                say("created file: $uri")
                step("write") {
                    contentResolver.openOutputStream(uri, "w")!!.use { it.write(payload) }
                    "${payload.size} bytes"
                }
            }.onFailure { say("create test aborted: ${it.javaClass.simpleName}: ${it.message}") }
        }.start()
    }

    private fun launchSafe(name: String, block: () -> Unit) {
        runCatching(block).onFailure {
            say("$name could not start: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private val pickerCandidates = listOf(
        "com.android.documentsui", "com.google.android.documentsui",
        "com.google.android.apps.nbu.files", "com.oplus.filemanager", "com.coloros.filemanager"
    )

    @Suppress("DEPRECATION")
    private fun candidates(): String = "Picker candidates:\n" + pickerCandidates.joinToString("\n") { pkg ->
        val state = runCatching {
            val info = packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            when {
                (info.flags and ApplicationInfo.FLAG_INSTALLED) == 0 -> "known, not installed here"
                !info.enabled -> "installed, disabled"
                else -> "installed, enabled"
            }
        }.getOrDefault("not found")
        "  $pkg: $state"
    }

    private fun forwarders(): String {
        fun hits(action: String, type: String?): String {
            val intent = Intent(action).apply { if (type != null) setType(type) }
            val found = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .filter { it.activityInfo.packageName == "android" }
                .map { it.activityInfo.name.substringAfterLast('.') }
            return if (found.isEmpty()) "none" else found.joinToString()
        }
        return "Forwarders to the other profile:\n" +
                "  SEND */*: ${hits(Intent.ACTION_SEND, "*/*")}\n" +
                "  SEND_MULTIPLE */*: ${hits(Intent.ACTION_SEND_MULTIPLE, "*/*")}\n" +
                "  OPEN_DOCUMENT_TREE: ${hits(Intent.ACTION_OPEN_DOCUMENT_TREE, null)}\n" +
                "  OPEN_DOCUMENT */*: ${hits(Intent.ACTION_OPEN_DOCUMENT, "*/*")}"
    }

    private fun bridgeDir() = File(cacheDir, "bridge")

    private fun bridgeSize(): Long =
        bridgeDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun clearBridge() {
        Thread {
            val before = bridgeSize()
            val ok = bridgeDir().deleteRecursively()
            say("cleared cache/bridge: ${before / 1048576} MB freed, ok=$ok")
        }.start()
    }

    private fun sendGrant(uri: Uri, prefix: Boolean) {
        val send = Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, uri)
        send.clipData = ClipData.newRawUri("grant", uri)
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (prefix) flags = flags or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        send.addFlags(flags)
        say("sharing $uri")
        runCatching { startActivity(Intent.createChooser(send, "Share grant")) }
            .onFailure { say("  share FAILED: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun shareChildFile(tree: Uri) {
        Thread {
            val doc = runCatching {
                val root = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree)
                )
                DocumentsContract.createDocument(
                    contentResolver, root, "text/plain",
                    TransferProbeUtil.PROBE_DOC_PREFIX + System.currentTimeMillis() + ".txt"
                )!!.also { d -> contentResolver.openOutputStream(d, "w")!!.use { it.write(payload) } }
            }.onFailure { say("create FAILED: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
            if (doc != null) runOnUiThread { sendGrant(doc, false) }
        }.start()
    }

    private fun fillAndShare(tree: Uri) {
        Thread {
            val t0 = System.currentTimeMillis()
            val ok = runCatching {
                val resolver = contentResolver
                val root = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree)
                )
                val chunk = ByteArray(4096) { (it % 251).toByte() }
                fun put(parent: Uri, name: String) {
                    val d = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)!!
                    resolver.openOutputStream(d, "w")!!.use { it.write(chunk) }
                }
                val folder = DocumentsContract.createDocument(
                    resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, TransferProbeUtil.TEST_FOLDER
                )!!
                repeat(300) { put(folder, "f$it.bin") }
                val sub = DocumentsContract.createDocument(
                    resolver, folder, DocumentsContract.Document.MIME_TYPE_DIR, "sub"
                )!!
                repeat(50) { put(sub, "s$it.bin") }
            }.onFailure { say("fill FAILED: ${it.javaClass.simpleName}: ${it.message}") }.isSuccess
            if (ok) {
                say("created 350 files in ${System.currentTimeMillis() - t0} ms")
                runOnUiThread { sendGrant(tree, true) }
            }
        }.start()
    }
}