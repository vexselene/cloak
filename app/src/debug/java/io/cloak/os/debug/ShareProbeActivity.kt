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
    @Volatile private var reading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uris = TransferProbeUtil.sharedUris(intent)
        out = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        column.addView(Button(this).apply { text = "Read now"; setOnClickListener { read(false) } })
        column.addView(Button(this).apply {
            text = "Read slowly (about 2 MB/s)"
            setOnClickListener { read(true) }
        })
        column.addView(out)
        setContentView(ScrollView(this).apply { addView(column) })

        say("Received in: ${TransferProbeUtil.where(this)}")
        say("action=${intent.action} type=${intent.type} uris=${uris.size}")
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { say("text length=${it.length}") }
    }

    private fun read(slow: Boolean) {
        if (reading) {
            say("already reading")
            return
        }
        reading = true
        say(if (slow) "reading slowly..." else "reading...")
        Thread {
            uris.forEach { uri -> say(TransferProbeUtil.inspect(this, uri, slow)) }
            say("done")
            reading = false
        }.start()
    }

    private fun say(msg: String) {
        Log.d(TransferProbeUtil.TAG, msg)
        runOnUiThread { out.append(msg + "\n") }
    }
}