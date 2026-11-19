package io.cloak.os.transfer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

object SourceScanner {
    private const val MAX_ENTRIES = 200_000

    /** Every file and folder under a tree. Throws if a listing fails, so a failure never looks empty. */
    fun listAll(context: Context, tree: Uri): List<DocEntry> {
        val out = ArrayList<DocEntry>()
        val stack = ArrayDeque<String>()
        stack.add(DocumentsContract.getTreeDocumentId(tree))
        while (stack.isNotEmpty()) {
            for (c in DocTree.children(context, tree, stack.removeLast(), strict = true)) {
                out.add(c)
                if (c.isDir) stack.add(c.id)
                if (out.size > MAX_ENTRIES) throw IOException("too many entries")
            }
        }
        return out
    }

    /** File count and total bytes. Bytes is -1 when any size is unknown, which blocks deletion. */
    fun snapshot(context: Context, src: Uri): SourceSnap {
        if (!DocTree.isTree(src)) return SourceSnap(1, DocTree.describe(context, src).size)
        var files = 0
        var bytes = 0L
        var unknown = false
        for (e in listAll(context, src)) {
            if (e.isDir) continue
            files++
            if (e.size < 0) unknown = true else bytes += e.size
        }
        return SourceSnap(files, if (unknown) -1L else bytes)
    }

    fun deleteRetry(resolver: ContentResolver, uri: Uri): Boolean {
        repeat(4) { i ->
            if (runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)) return true
            Thread.sleep(150L * (i + 1))
        }
        return false
    }

    /** Deletes a source in this profile. Files first, then folders deepest first. */
    fun delete(context: Context, src: Uri, cancelled: () -> Boolean, onFile: () -> Unit): Boolean {
        val resolver = context.contentResolver
        if (!DocTree.isTree(src)) {
            val ok = deleteRetry(resolver, src)
            if (ok) onFile()
            return ok
        }
        val all = listAll(context, src)
        var ok = true
        for (e in all.filter { !it.isDir }) {
            if (cancelled()) return false
            if (deleteRetry(resolver, DocumentsContract.buildDocumentUriUsingTree(src, e.id))) onFile() else ok = false
        }
        for (e in all.filter { it.isDir }.asReversed()) {
            if (cancelled()) return false
            if (!deleteRetry(resolver, DocumentsContract.buildDocumentUriUsingTree(src, e.id))) ok = false
        }
        if (ok) {
            val root = DocumentsContract.buildDocumentUriUsingTree(src, DocumentsContract.getTreeDocumentId(src))
            ok = deleteRetry(resolver, root)
        }
        return ok
    }
}