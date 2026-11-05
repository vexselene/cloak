package io.cloak.os.debug

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.cloak.os.bridge.Commands
import io.cloak.os.common.Role
import io.cloak.os.ui.rememberSpaceState
import java.io.File
import androidx.compose.runtime.LaunchedEffect
import android.os.Handler
import android.os.Looper
import android.content.Intent
private const val TAG = "SecondSpace/Debug"

@Composable
fun DebugPanel(role: Role) {
    val context = LocalContext.current
    if (role == Role.MAIN) {
        val probeState by rememberSpaceState()
        LaunchedEffect(probeState) { OwnershipProbe.run(context) }
    }
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide debug" else "Debug") }
        if (open) {
            val updated = remember {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            }
            Text("Build updated: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(updated))}")
            Text("Role: $role")
            if (role == Role.MAIN) {
                val state by rememberSpaceState()
                Text("Space state: $state")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        Commands.send(context, Commands.PING).onFailure { Log.e(TAG, "ping failed", it) }
                    }) { Text("Ping") }
                    Button(onClick = { fileTest(context) }) { Text("File test") }
                }
                var probe by remember { mutableStateOf("") }
                Button(onClick = { probe = OwnershipProbe.run(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Probe ownership")
                }
                if (probe.isNotEmpty()) Text(probe)
                SpikePanel(context)
            } else {
                val dpm = remember { context.getSystemService(DevicePolicyManager::class.java) }
                Text("Profile owner: ${dpm.isProfileOwnerApp(context.packageName)}")
            }
        }
    }
}

@Composable
private fun SpikePanel(context: Context) {
    var pkg by remember { mutableStateOf("") }
    fun spike(arg: String) {
        Commands.send(context, Commands.SPIKE, arg).onFailure { Log.e(TAG, "spike send failed", it) }
    }
    @Composable
    fun Pair(a: String, onA: () -> Unit, b: String, onB: () -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onA, modifier = Modifier.weight(1f)) { Text(a) }
            Button(onClick = onB, modifier = Modifier.weight(1f)) { Text(b) }
        }
    }

    Text("Spike 1: hide / unhide")
    OutlinedTextField(
        value = pkg,
        onValueChange = { pkg = it.trim() },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Package (e.g. com.android.chrome)") }
    )
    Pair("Hide", { spike("hide:$pkg") }, "Unhide", { spike("unhide:$pkg") })
    Pair("Hide all", { spike("hideall") }, "Unhide all", { spike("unhideall") })
    Button(onClick = { spike("status") }, modifier = Modifier.fillMaxWidth()) { Text("Hidden status") }

    Text("Spike 2: screen-off listener")
    Pair("Probe: service", { spike("probe_fgs") }, "Probe: plain", { spike("probe_plain") })
    Pair("Probe status", { spike("probe_status") }, "Stop probe", { spike("probe_stop") })
    Button(onClick = { spike("battery") }, modifier = Modifier.fillMaxWidth()) { Text("Battery exemption") }
    Text("Spike 3: work PIN prompt")
    Text("Spike 4: share sheet filters (space to personal)")
    Pair("Share filters ON", { spike("share_on") }, "ON (both directions)", { spike("share_on_both") })
    Button(onClick = { spike("share_off") }, modifier = Modifier.fillMaxWidth()) { Text("Share filters OFF") }
    Button(onClick = { spike("enable:$pkg") }, modifier = Modifier.fillMaxWidth()) {
        Text("Enable system app in space (package above)")
    }
    Text("Spike 4 and 5: transfer tools")
    Pair(
        "Tools (personal)", { context.startActivity(Intent(context, TransferProbeActivity::class.java)) },
        "Tools (space)", { spike("picker") }
    )
    Pair(
        "PIN prompt now", { spike("pin") },
        "Lock, then PIN (4 s)", {
            Commands.send(context, Commands.LOCK_NOW)
            Handler(Looper.getMainLooper()).postDelayed({ spike("pin") }, 4_000L)
        }
    )
}

private fun fileTest(context: Context) {
    runCatching {
        val dir = File(context.cacheDir, "bridge").apply { mkdirs() }
        val f = File(dir, "test.txt").apply { writeText("hello ${System.currentTimeMillis()}") }
        val uri = FileProvider.getUriForFile(context, "io.cloak.os.fileprovider", f)
        Commands.send(context, Commands.FILE_TEST, "test.txt", listOf(uri)).getOrThrow()
    }.onFailure { Log.e(TAG, "file test failed", it) }
}