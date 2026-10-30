package io.cloak.os

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.cloak.os.admin.PolicyManager
import io.cloak.os.admin.SpaceAdminReceiver
import io.cloak.os.bridge.Auth
import io.cloak.os.bridge.Commands
import io.cloak.os.bridge.SpaceState
import io.cloak.os.bridge.SpaceStateReader
import io.cloak.os.common.Role
import io.cloak.os.ui.CloneList
import io.cloak.os.ui.SpaceGrid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.cloak.os.debug.DebugPanel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { HomeScreen() }
            }
        }
    }
}

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val role = remember { Role.detect(context) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DebugPanel(role)
        when (role) {
            Role.AGENT -> AgentScreen()
            Role.MAIN -> MainScreen()
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

@Composable
private fun ColumnScope.MainScreen() {
    val context = LocalContext.current
    val dpm = remember { context.getSystemService(DevicePolicyManager::class.java) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(SpaceStateReader.read(context)) }
    var showAdd by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) { state = SpaceStateReader.read(context); delay(1000) }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        state = SpaceStateReader.read(context)
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                delay(3000)
                repeat(30) { i ->
                    val st = SpaceStateReader.read(context)
                    val r = if (st != SpaceState.NONE) Commands.send(context, Commands.SET_LOCK) else null
                    Log.d("SecondSpace/Main", "set_lock attempt $i state=$st result=$r")
                    if (r?.isSuccess == true) return@launch
                    delay(1000)
                }
            }
        }
    }

    when (state) {
        SpaceState.NONE -> {
            val allowed = dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
            Button(
                enabled = allowed,
                onClick = {
                    val secret = Auth.generateSecret()
                    Auth.saveSecret(context, secret)
                    val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
                        .putExtra(
                            DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                            SpaceAdminReceiver.component(context)
                        )
                        .putExtra(
                            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                            android.os.PersistableBundle().apply { putString(Auth.BUNDLE_SECRET, secret) }
                        )
                    launcher.launch(intent)
                }
            ) { Text(stringResource(R.string.create_space)) }
            if (!allowed) Text(stringResource(R.string.provisioning_not_allowed))
        }
        SpaceState.PAUSED, SpaceState.LOCKED -> {
            Text(if (state == SpaceState.PAUSED) "Second Space is paused" else "Second Space is locked")
            Button(onClick = { Commands.send(context, Commands.UNLOCK) }) { Text("Unlock") }
        }
        SpaceState.UNLOCKED -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showAdd = !showAdd }, modifier = Modifier.weight(1f)) {
                    Text(if (showAdd) "Back" else "Add apps")
                }
                Button(onClick = { Commands.send(context, Commands.LOCK_NOW) }, modifier = Modifier.weight(1f)) {
                    Text("Lock")
                }
                Button(onClick = { Commands.send(context, Commands.SET_LOCK) }, modifier = Modifier.weight(1f)) {
                    Text("Set lock")
                }
            }
            if (showAdd) CloneList(Modifier.weight(1f)) else SpaceGrid(Modifier.weight(1f))
        }
    }
}