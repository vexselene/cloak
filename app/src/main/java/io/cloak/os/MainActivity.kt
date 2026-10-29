package io.cloak.os

import android.app.admin.DevicePolicyManager
import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.content.Intent
import io.cloak.os.admin.SpaceAdminReceiver
import io.cloak.os.common.Role
import android.content.pm.LauncherApps
import android.os.Process
import io.cloak.os.admin.PolicyManager
import android.util.Log
import android.content.pm.CrossProfileApps
import androidx.compose.runtime.LaunchedEffect
import io.cloak.os.bridge.Commands
import io.cloak.os.bridge.SpaceStateReader
import kotlinx.coroutines.delay
import io.cloak.os.bridge.Auth
import android.app.Activity
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import io.cloak.os.bridge.SpaceState
import io.cloak.os.ui.CloneList

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
    val dpm = remember { context.getSystemService(DevicePolicyManager::class.java) }
    val um = remember { context.getSystemService(UserManager::class.java) }

    val launcherApps = remember { context.getSystemService(LauncherApps::class.java) }
    fun hasOurSpace(): Boolean = um.userProfiles
        .filter { it != Process.myUserHandle() }
        .any { launcherApps.getActivityList(context.packageName, it).isNotEmpty() }

    var spaceExists by remember { mutableStateOf(hasOurSpace()) }

    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        spaceExists = hasOurSpace()
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                repeat(30) { // retries the lock prompt for up to 30 seconds after provisioning
                    if (SpaceStateReader.read(context) != SpaceState.NONE &&
                        Commands.send(context, Commands.SET_LOCK).isSuccess
                    ) return@launch
                    delay(1000)
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val updated = remember { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }
        Text("Build updated: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(updated))}")
        when (role) {
            Role.AGENT -> {
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
            Role.MAIN -> {
                val allowed = dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)

                var state by remember { mutableStateOf(SpaceStateReader.read(context)) }
                LaunchedEffect(Unit) {
                    while (true) { state = SpaceStateReader.read(context); delay(1000) }
                }
                if (spaceExists) {
                    Text(stringResource(R.string.space_exists))
                    Button(onClick = {
                        Commands.send(context, Commands.PING).onFailure { Log.e("SecondSpace/Main", "ping failed", it) }
                    }) { Text("Ping Second Space") }
                    Text("Space state: $state")
                    Button(onClick = { Commands.send(context, Commands.LOCK_NOW) }) { Text("Lock Second Space") }
                    CloneList(Modifier.weight(1f))
                    Button(onClick = { Commands.send(context, Commands.SET_LOCK) }) { Text("Set / change lock") }
                } else {
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
            }
        }
    }
}