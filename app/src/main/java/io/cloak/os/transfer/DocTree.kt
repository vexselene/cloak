package io.cloak.os.transfer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.IOException

class DocEntry(val id: String, val name: String, val mime: String, val size: Long) {
    val isDir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
}

class SourceInfo(val name: String, val mime: String, val size: Long)

object DocTree {
    /** content://authority/tree/<id> */
    fun isTree(uri: Uri): Boolean = uri.pathSegments.let { it.size == 2 && it[0] == "tree" }

    /** A single document, with or without a tree in front of it. */
    fun isDocument(uri: Uri): Boolean = uri.pathSegments.let {
        (it.size == 2 && it[0] == "document") || (it.size == 4 && it[0] == "tree" && it[2] == "document")
    }

    fun rootName(context: Context, tree: Uri): String? = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(
            doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** With strict = true a provider that returns no cursor throws instead of looking empty. */
    fun children(context: Context, tree: Uri, parentId: String, strict: Boolean = false): List<DocEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val out = ArrayList<DocEntry>()
        val cursor = context.contentResolver.query(uri, cols, null, null, null)
        if (cursor == null) {
            if (strict) throw IOException("listing failed")
            return out
        }
        cursor.use { c ->
            while (c.moveToNext()) {
                out.add(
                    DocEntry(
                        c.getString(0),
                        c.getString(1) ?: "?",
                        c.getString(2) ?: "",
                        if (c.isNull(3)) -1L else c.getLong(3)
                    )
                )
            }
        }
        return out
    }

    fun describe(context: Context, uri: Uri): SourceInfo {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: "application/octet-stream"
        return SourceInfo(name ?: uri.lastPathSegment ?: "file", mime, size)
    }
}