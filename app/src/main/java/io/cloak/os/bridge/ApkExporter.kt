package io.cloak.os.bridge

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object ApkExporter {
    const val AUTHORITY = "io.cloak.os.fileprovider"

    private fun root(context: Context) = File(context.cacheDir, "bridge")

    fun export(context: Context, packageName: String): Result<List<Uri>> = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        val paths = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
        val root = root(context)
        root.deleteRecursively()
        val dir = File(root, packageName).apply { mkdirs() }
        paths.map { p ->
            val src = File(p)
            val dst = File(dir, src.name)
            src.copyTo(dst, overwrite = true)
            FileProvider.getUriForFile(context, AUTHORITY, dst)
        }
    }

    /** Deletes exported copies. With olderThanMs > 0, only files at least that old. */
    fun cleanup(context: Context, olderThanMs: Long = 0L) {
        val root = root(context)
        if (!root.exists()) return
        if (olderThanMs <= 0L) {
            root.deleteRecursively()
            return
        }
        val cutoff = System.currentTimeMillis() - olderThanMs
        root.walkBottomUp()
            .filter { it != root && it.lastModified() < cutoff }
            .forEach { if (it.isFile || it.list().isNullOrEmpty()) it.delete() }
    }
}