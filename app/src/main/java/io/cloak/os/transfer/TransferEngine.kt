package io.cloak.os.transfer

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import io.cloak.os.common.Logx
import java.io.IOException

/** Pulls files and folders into a folder tree picked in this profile. Runs on one background thread. */
class TransferEngine(
    context: Context,
    private val job: TransferJob,
    private val onState: (TransferState) -> Unit
) {
    @Volatile
    var cancelled = false

    private class Cancelled : RuntimeException()
    private class PlanFile(val dir: List<String>, val name: String, val uri: Uri, val mime: String, val size: Long)
    private class Plan(val dirs: List<List<String>>, val files: List<PlanFile>)
    private enum class Outcome { COPIED, SKIPPED, FAILED }

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val dirMime = DocumentsContract.Document.MIME_TYPE_DIR
    private val dirUris = HashMap<List<String>, Uri>()
    private val listings = HashMap<Uri, Map<String, DocEntry>>()
    private val recent = ArrayDeque<String>()

    private var filesTotal = 0
    private var bytesTotal = 0L
    private var processed = 0
    private var copied = 0
    private var skipped = 0
    private var failed = 0
    private var bytesDone = 0L
    private var interrupted = false
    private var current = ""
    private var lastPublish = 0L

    fun run(): TransferState {
        publish(Phase.PREPARING, force = true)
        val plan = try {
            buildPlan()
        } catch (t: Throwable) {
            Logx.e(TAG, "planning failed", t)
            return finish(Phase.FAILED)
        }
        if (cancelled) return finish(Phase.CANCELLED)
        filesTotal = plan.files.size
        bytesTotal = plan.files.sumOf { maxOf(it.size, 0L) }
        if (filesTotal == 0 && plan.dirs.isEmpty()) return finish(Phase.FAILED)
        publish(Phase.COPYING, force = true)

        dirUris[emptyList()] = DocumentsContract.buildDocumentUriUsingTree(
            job.destTree, DocumentsContract.getTreeDocumentId(job.destTree)
        )
        try {
            for (path in plan.dirs) {
                if (cancelled) throw Cancelled()
                ensureDir(path)
            }
            for (f in plan.files) {
                if (cancelled) throw Cancelled()
                if (interrupted) break
                current = f.name
                val dir = ensureDir(f.dir)
                val outcome = if (dir == null) Outcome.FAILED else copyFile(f, dir)
                processed++
                when (outcome) {
                    Outcome.COPIED -> {
                        copied++
                        remember(f.name)
                    }
                    Outcome.SKIPPED -> skipped++
                    Outcome.FAILED -> failed++
                }
                publish(Phase.COPYING, force = false)
            }
        } catch (_: Cancelled) {
            return finish(Phase.CANCELLED)
        }
        return finish(if (interrupted) Phase.FAILED else Phase.DONE)
    }

    private fun buildPlan(): Plan {
        val dirs = ArrayList<List<String>>()
        val files = ArrayList<PlanFile>()
        for (src in job.sources) {
            if (cancelled) break
            if (DocTree.isTree(src)) {
                val top = listOf(TransferFormat.safeName(DocTree.rootName(appContext, src), "Folder"))
                dirs.add(top)
                val stack = ArrayDeque<Pair<String, List<String>>>()
                stack.add(DocumentsContract.getTreeDocumentId(src) to top)
                while (stack.isNotEmpty()) {
                    if (cancelled) break
                    val (id, path) = stack.removeLast()
                    for (c in DocTree.children(appContext, src, id)) {
                        val name = TransferFormat.safeName(c.name, "item")
                        if (c.isDir) {
                            val p = path + name
                            dirs.add(p)
                            stack.add(c.id to p)
                        } else {
                            files.add(
                                PlanFile(
                                    path, name,
                                    DocumentsContract.buildDocumentUriUsingTree(src, c.id),
                                    c.mime.ifEmpty { "application/octet-stream" }, c.size
                                )
                            )
                        }
                    }
                }
            } else {
                val info = DocTree.describe(appContext, src)
                files.add(PlanFile(emptyList(), TransferFormat.safeName(info.name, "file"), src, info.mime, info.size))
            }
        }
        return Plan(dirs, files)
    }

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(job.destTree, id)

    private fun listing(dir: Uri): Map<String, DocEntry> = listings.getOrPut(dir) {
        runCatching { DocTree.children(appContext, job.destTree, DocumentsContract.getDocumentId(dir)) }
            .getOrDefault(emptyList())
            .associateBy { it.name }
    }

    private fun ensureDir(path: List<String>): Uri? {
        dirUris[path]?.let { return it }
        val parent = ensureDir(path.dropLast(1)) ?: return null
        val name = path.last()
        val existing = listing(parent)[name]
        val uri = if (existing != null && existing.isDir) {
            docUri(existing.id)
        } else {
            runCatching { DocumentsContract.createDocument(resolver, parent, dirMime, name) }
                .onFailure { Logx.e(TAG, "create folder failed", it) }
                .getOrNull()
        }
        if (uri != null) dirUris[path] = uri
        return uri
    }

    private fun copyFile(f: PlanFile, dir: Uri): Outcome {
        val existing = listing(dir)[f.name]?.takeIf { !it.isDir }
        if (existing != null && job.policy == ConflictPolicy.SKIP) return Outcome.SKIPPED

        val target: Uri
        val created: Boolean
        if (existing != null && job.policy == ConflictPolicy.REPLACE) {
            target = docUri(existing.id)
            created = false
        } else {
            target = runCatching { DocumentsContract.createDocument(resolver, dir, f.mime, f.name) }
                .onFailure { Logx.e(TAG, "create file failed", it) }
                .getOrNull() ?: return Outcome.FAILED
            created = true
        }

        var written = 0L
        try {
            val input = resolver.openInputStream(f.uri) ?: throw IOException("no input")
            input.use { ins ->
                val output = resolver.openOutputStream(target, "wt") ?: throw IOException("no output")
                output.use { outs ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        if (cancelled) throw Cancelled()
                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                        written += n
                        bytesDone += n
                        publish(Phase.COPYING, force = false)
                    }
                }
            }
        } catch (c: Cancelled) {
            if (created) deleteQuiet(target)
            throw c
        } catch (e: SecurityException) {
            interrupted = true
            if (created) deleteQuiet(target)
            Logx.e(TAG, "access lost", e)
            return Outcome.FAILED
        } catch (e: Exception) {
            if (created) deleteQuiet(target)
            Logx.e(TAG, "copy failed", e)
            return Outcome.FAILED
        }
        if (f.size >= 0 && written != f.size) {
            Logx.w(TAG, "size mismatch")
            if (created) deleteQuiet(target)
            return Outcome.FAILED
        }
        return Outcome.COPIED
    }

    private fun deleteQuiet(uri: Uri) {
        repeat(3) { i ->
            if (runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)) return
            Thread.sleep(150L * (i + 1))
        }
    }

    private fun remember(name: String) {
        recent.addLast(name)
        while (recent.size > 3) recent.removeFirst()
    }

    private fun snapshot(phase: Phase) = TransferState(
        phase, processed, filesTotal, copied, skipped, failed,
        bytesDone, bytesTotal, current, recent.toList(), interrupted
    )

    private fun publish(phase: Phase, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublish < PUBLISH_MS) return
        lastPublish = now
        onState(snapshot(phase))
    }

    private fun finish(phase: Phase): TransferState = snapshot(phase).copy(current = "")

    private companion object {
        const val TAG = "SecondSpace/Transfer"
        const val BUFFER = 256 * 1024
        const val PUBLISH_MS = 250L
    }
}