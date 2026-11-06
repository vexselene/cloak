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

    /** Filled in when run() returns, only for in-app transfers that have an id. */
    @Volatile
    var report: TransferReport? = null
        private set

    private class Cancelled : RuntimeException()
    private class PlanDir(val src: Int, val path: List<String>)
    private class PlanFile(
        val src: Int, val dir: List<String>, val name: String,
        val uri: Uri, val mime: String, val size: Long
    )
    private class Plan(val dirs: List<PlanDir>, val files: List<PlanFile>)
    private enum class Outcome { COPIED, SKIPPED, FAILED }

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val dirMime = DocumentsContract.Document.MIME_TYPE_DIR
    private val dirUris = HashMap<List<String>, Uri>()
    private val listings = HashMap<Uri, Map<String, DocEntry>>()
    private val recent = ArrayDeque<String>()

    private val count = job.sources.size
    private val planned = IntArray(count)
    private val done = IntArray(count)
    private val doneBytes = LongArray(count)
    private val bad = BooleanArray(count)

    private var filesTotal = 0
    private var bytesTotal = 0L
    private var processed = 0
    private var copied = 0
    private var skipped = 0
    private var failed = 0
    private var bytesDone = 0L
    private var interrupted = false
    private var current = ""
    private var lastWritten = 0L
    private var lastPublish = 0L

    fun run(): TransferState {
        publish(Phase.PREPARING, force = true)
        val plan = buildPlan()
        if (cancelled) return finish(Phase.CANCELLED)
        plan.files.forEach { planned[it.src]++ }
        filesTotal = plan.files.size
        bytesTotal = plan.files.sumOf { maxOf(it.size, 0L) }
        if (filesTotal == 0 && plan.dirs.isEmpty()) return finish(Phase.FAILED)
        publish(Phase.COPYING, force = true)

        dirUris[emptyList()] = DocumentsContract.buildDocumentUriUsingTree(
            job.destTree, DocumentsContract.getTreeDocumentId(job.destTree)
        )
        try {
            for (d in plan.dirs) {
                if (cancelled) throw Cancelled()
                if (ensureDir(d.path) == null) bad[d.src] = true
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
                        done[f.src]++
                        doneBytes[f.src] += lastWritten
                        remember(f.name)
                    }
                    Outcome.SKIPPED -> {
                        skipped++
                        bad[f.src] = true
                    }
                    Outcome.FAILED -> {
                        failed++
                        bad[f.src] = true
                    }
                }
                publish(Phase.COPYING, force = false)
            }
        } catch (_: Cancelled) {
            return finish(Phase.CANCELLED)
        }
        return finish(if (interrupted) Phase.FAILED else Phase.DONE)
    }

    private fun buildPlan(): Plan {
        val dirs = ArrayList<PlanDir>()
        val files = ArrayList<PlanFile>()
        job.sources.forEachIndexed { index, src ->
            if (cancelled) return@forEachIndexed
            try {
                if (DocTree.isTree(src)) {
                    val top = listOf(TransferFormat.safeName(DocTree.rootName(appContext, src), "Folder"))
                    dirs.add(PlanDir(index, top))
                    val stack = ArrayDeque<Pair<String, List<String>>>()
                    stack.add(DocumentsContract.getTreeDocumentId(src) to top)
                    while (stack.isNotEmpty()) {
                        if (cancelled) break
                        val (id, path) = stack.removeLast()
                        for (c in DocTree.children(appContext, src, id, strict = true)) {
                            val name = TransferFormat.safeName(c.name, "item")
                            if (c.isDir) {
                                val p = path + name
                                dirs.add(PlanDir(index, p))
                                stack.add(c.id to p)
                            } else {
                                files.add(
                                    PlanFile(
                                        index, path, name,
                                        DocumentsContract.buildDocumentUriUsingTree(src, c.id),
                                        c.mime.ifEmpty { "application/octet-stream" }, c.size
                                    )
                                )
                            }
                        }
                    }
                } else {
                    val info = DocTree.describe(appContext, src)
                    files.add(
                        PlanFile(
                            index, emptyList(), TransferFormat.safeName(info.name, "file"),
                            src, info.mime, info.size
                        )
                    )
                }
            } catch (e: Exception) {
                Logx.e(TAG, "planning a source failed", e)
                bad[index] = true
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
        if (job.op == Op.MOVE) {
            // A move deletes the original, so check what actually landed on disk.
            val onDisk = DocTree.describe(appContext, target).size
            if (onDisk >= 0 && onDisk != written) {
                Logx.w(TAG, "destination size differs")
                if (created) deleteQuiet(target)
                return Outcome.FAILED
            }
        }
        lastWritten = written
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
        phase = phase, filesDone = processed, filesTotal = filesTotal,
        copied = copied, skipped = skipped, failed = failed,
        bytesDone = bytesDone, bytesTotal = bytesTotal,
        current = current, recent = recent.toList(), interrupted = interrupted, op = job.op
    )

    private fun publish(phase: Phase, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublish < PUBLISH_MS) return
        lastPublish = now
        onState(snapshot(phase))
    }

    /** Only sources that were planned fully and copied without a single problem are reported. */
    private fun buildReport(phase: Phase): TransferReport? {
        val id = job.id ?: return null
        val results = (0 until count)
            .filter { !bad[it] && done[it] == planned[it] }
            .map { SourceResult(it, done[it], doneBytes[it]) }
        return TransferReport(id, job.op, phase == Phase.DONE, results)
    }

    private fun finish(phase: Phase): TransferState {
        report = buildReport(phase)
        return snapshot(phase).copy(
            current = "",
            needsSettle = phase == Phase.DONE && job.op == Op.MOVE && job.id != null
        )
    }

    private companion object {
        const val TAG = "SecondSpace/Transfer"
        const val BUFFER = 256 * 1024
        const val PUBLISH_MS = 250L
    }
}