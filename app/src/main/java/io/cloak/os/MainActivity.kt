package io.cloak.os

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.cloak.os.bridge.ApkExporter
import io.cloak.os.common.Role
import io.cloak.os.ui.HomeScreen
import io.cloak.os.ui.theme.spaceBackground
import io.cloak.os.ui.theme.SpaceTheme
import android.content.Intent
import io.cloak.os.bridge.Commands
import io.cloak.os.bridge.SpaceEvents
import io.cloak.os.common.Logx
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Role.detect(this) == Role.AGENT) {
            finish()
            handleEvent(intent)
            return
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        val appContext = applicationContext
        Thread { ApkExporter.cleanup(appContext, STALE_EXPORT_MS) }.start()
        setContent {
            SpaceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().background(spaceBackground()),
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) { HomeScreen() }
            }
        }
    }

    private companion object {
        const val STALE_EXPORT_MS = 10 * 60 * 1000L
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleEvent(intent)
    }

    private fun handleEvent(intent: Intent?) {
        if (intent?.action != Commands.EVENT_ACTION) return
        val name = intent.getStringExtra(Commands.EXTRA_EVENT)
        val known = SpaceEvents.isKnown(name)
        Logx.d("SecondSpace/Event", "event received: ${if (known) name else "unknown"}")
        if (known) SpaceEvents.notifyMain()
    }
}