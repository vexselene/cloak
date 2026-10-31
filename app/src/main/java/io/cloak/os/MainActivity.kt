package io.cloak.os

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.cloak.os.admin.PolicyManager
import io.cloak.os.common.Role
import io.cloak.os.debug.DebugPanel
import io.cloak.os.ui.HomeScreen
import io.cloak.os.ui.theme.SpaceBackground
import io.cloak.os.ui.theme.SpaceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        val role = Role.detect(this)
        setContent {
            SpaceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().background(SpaceBackground),
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) {
                    if (role == Role.AGENT) AgentScreen() else HomeScreen()
                }
            }
        }
    }
}

@Composable
private fun AgentScreen() {
    val context = LocalContext.current
    val dpm = remember { context.getSystemService(DevicePolicyManager::class.java) }
    LaunchedEffect(Unit) {
        PolicyManager.allowCrossProfile(context)
            .onFailure { Log.e("SecondSpace/Main", "allowCrossProfile failed", it) }
    }
    LaunchedEffect(Unit) { PolicyManager.setupChannel(context) }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DebugPanel(Role.AGENT)
        Text(stringResource(R.string.running_in_space))
        Text(
            stringResource(
                if (dpm.isProfileOwnerApp(context.packageName)) R.string.owner_yes else R.string.owner_no
            )
        )
        Button(onClick = {
            PolicyManager.requireWorkLock(context)
                .onFailure { Log.e("SecondSpace/Main", "requireWorkLock failed", it) }
            runCatching {
                context.startActivity(
                    Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD).putExtra(
                        DevicePolicyManager.EXTRA_PASSWORD_COMPLEXITY,
                        DevicePolicyManager.PASSWORD_COMPLEXITY_LOW
                    )
                )
            }.onFailure { Log.e("SecondSpace/Main", "lock screen launch failed", it) }
        }) { Text(stringResource(R.string.set_lock)) }
    }
}