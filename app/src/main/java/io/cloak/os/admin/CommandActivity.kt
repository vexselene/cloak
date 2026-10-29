package io.cloak.os.admin

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.Toast

class CommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("SecondSpace/Command", "action=${intent.action} user=${Process.myUserHandle()}")
        Toast.makeText(this, "Ping received in Second Space", Toast.LENGTH_SHORT).show()
        finish()
    }
}