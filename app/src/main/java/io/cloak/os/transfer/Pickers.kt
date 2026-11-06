package io.cloak.os.transfer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

object Pickers {
    private const val KEEP = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

    /** Several files, with access that can be kept so originals can be removed later. */
    class Files : ActivityResultContract<Unit, List<Uri>>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(KEEP)

        override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
            if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
            val out = LinkedHashSet<Uri>()
            intent.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { out.add(it) } }
            intent.data?.let { out.add(it) }
            return out.take(TransferFlows.MAX_SOURCES)
        }
    }

    class Folder : ActivityResultContract<Unit, Uri?>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(KEEP or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == Activity.RESULT_OK) intent?.data else null
    }
}