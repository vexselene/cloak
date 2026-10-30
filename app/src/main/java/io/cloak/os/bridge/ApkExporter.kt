package io.cloak.os.bridge

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object ApkExporter {
    fun export(context: Context, packageName: String): Result<List<Uri>> = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        val paths = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
        val root = File(context.cacheDir, "bridge")
        root.deleteRecursively()
        val dir = File(root, packageName).apply { mkdirs() }
        paths.map { p ->
            val src = File(p)
            val dst = File(dir, src.name)
            src.copyTo(dst, overwrite = true)
            FileProvider.getUriForFile(context, "io.cloak.os.fileprovider", dst)
        }
    }
}