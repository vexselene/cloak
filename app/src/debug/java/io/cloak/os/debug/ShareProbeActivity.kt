package io.cloak.os.debug

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ShareProbeActivity : Activity() {
    private lateinit var out: TextView
    private var uris: List<Uri> = emptyList()
    private var throttleMs = 0L
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uris = TransferProbeUtil.sharedUris(intent)
        out = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun button(label: String, action: () -> Unit) {
            column.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Read now") { runChecks(slow = false, onlySpecial = false) }
        button("Read slowly (about 2 MB/s)") { runChecks(slow = true, onlySpecial = false) }
        button("Copy into a folder (pick destination)") { pickDestination(0L) }
        button("Copy slowly (100 ms per file)") { pickDestination(100L) }
        column.addView(out)
        setContentView(ScrollView(this).apply { addView(column) })

        say("Received in: ${TransferProbeUtil.where(this)}")
        say("action=${intent.action} type=${intent.type} uris=${uris.size}")
        uris.forEach { say("  received uri: $it") }
        if (savedInstanceState == null) runChecks(slow = false, onlySpecial = true)
    }

    private fun runChecks(slow: Boolean, onlySpecial: Boolean) {
        if (busy) {
            say("busy")
            return
        }
        val targets = if (onlySpecial) {
            uris.filter { TransferProbeUtil.isPureTree(it) || TransferProbeUtil.isDocPath(it) }
        } else uris
        if (targets.isEmpty()) return
        busy = true
        say(if (slow) "reading slowly..." else "checking...")
        Thread {
            targets.forEach { uri ->
                when {
                    TransferProbeUtil.isPureTree(uri) -> TransferProbeUtil.testTree(this, uri, this::say)
                    TransferProbeUtil.isDocPath(uri) -> {
                        say(TransferProbeUtil.inspect(this, uri, slow))
                        TransferProbeUtil.testDoc(this, uri, this::say)
                    }
                    else -> say(TransferProbeUtil.inspect(this, uri, slow))
                }
            }
            say("done")
            busy = false
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun pickDestination(throttle: Long) {
        if (busy) {
            say("busy")
            return
        }
        throttleMs = throttle
        runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_DEST) }
            .onFailure { say("folder picker could not start: ${it.javaClass.simpleName}: ${it.message}") }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val dest = data?.data
        if (requestCode != REQUEST_DEST || resultCode != RESULT_OK || dest == null) return
        busy = true
        Thread {
            uris.forEach { uri ->
                say("copy: $uri")
                if (TransferProbeUtil.isPureTree(uri)) {
                    TransferProbeUtil.copyTree(this, uri, dest, throttleMs, this::say)
                } else {
                    TransferProbeUtil.copyOne(this, uri, dest, throttleMs, this::say)
                }
            }
            say("copy done")
            busy = false
        }.start()
    }

    private fun say(msg: String) {
        Log.d(TransferProbeUtil.TAG, msg)
        runOnUiThread { out.append(msg + "\n") }
    }

    private companion object {
        const val REQUEST_DEST = 1
    }
}