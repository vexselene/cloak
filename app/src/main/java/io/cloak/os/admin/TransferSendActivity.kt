package io.cloak.os.admin

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import io.cloak.os.R
import io.cloak.os.common.Logx
import io.cloak.os.transfer.DocTree
import io.cloak.os.transfer.Grants
import io.cloak.os.transfer.LedgerEntry
import io.cloak.os.transfer.MoveLedger
import io.cloak.os.transfer.Pickers
import io.cloak.os.transfer.ShareIntents
import io.cloak.os.transfer.TransferFlows

/** Runs inside the space. Picks files there, then hands them to Cloak in the personal profile. */
class TransferSendActivity : ComponentActivity() {
    private val pickFiles = registerForActivityResult(Pickers.Files()) { onPicked(it) }
    private val pickFolder = registerForActivityResult(Pickers.Folder()) { onPicked(listOfNotNull(it)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val folder = intent.getStringExtra(EXTRA_KIND) == "folder"
        runCatching { if (folder) pickFolder.launch(Unit) else pickFiles.launch(Unit) }
            .onFailure {
                Logx.e(TAG, "no picker in the space", it)
                Toast.makeText(this, R.string.transfer_no_picker_space, Toast.LENGTH_LONG).show()
                finish()
            }
    }

    private fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) {
            finish()
            return
        }
        val app = applicationContext
        Thread {
            val result = runCatching { TransferFlows.prepare(app, uris) }
            runOnUiThread {
                result
                    .onSuccess { forward(it) }
                    .onFailure {
                        Logx.e(TAG, "scan failed", it)
                        Toast.makeText(this, R.string.transfer_scan_failed, Toast.LENGTH_LONG).show()
                        finish()
                    }
            }
        }.start()
    }

    private fun forward(entry: LedgerEntry) {
        val intent = Intent(ShareIntents.ACTION_TRANSFER_IN).apply {
            type = "*/*"
            putExtra(ShareIntents.EXTRA_TRANSFER_ID, entry.id)
            val clip = ClipData.newRawUri("transfer", entry.sources.first())
            entry.sources.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            clipData = clip
            var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            if (entry.sources.any { DocTree.isTree(it) }) flags = flags or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            addFlags(flags)
        }
        Logx.d(TAG, "forwarding ${entry.sources.size} source(s) to personal")
        runCatching { startActivity(intent) }.onFailure {
            Logx.e(TAG, "could not reach personal Cloak", it)
            MoveLedger.remove(applicationContext, entry.id)
            Toast.makeText(this, R.string.transfer_send_failed, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        private const val TAG = "SecondSpace/TransferSend"
    }
}