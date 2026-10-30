package io.cloak.os.debug

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.cloak.os.bridge.Commands
import io.cloak.os.bridge.SpaceStateReader
import io.cloak.os.common.Role
import kotlinx.coroutines.delay
import java.io.File

private const val TAG = "SecondSpace/Debug"

@Composable
fun DebugPanel(role: Role) {
    val context = LocalContext.current
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
                var state by remember { mutableStateOf(SpaceStateReader.read(context)) }
                LaunchedEffect(Unit) {
                    while (true) { state = SpaceStateReader.read(context); delay(1000) }
                }
                Text("Space state: $state")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        Commands.send(context, Commands.PING).onFailure { Log.e(TAG, "ping failed", it) }
                    }) { Text("Ping") }
                    Button(onClick = { fileTest(context) }) { Text("File test") }
                }
            } else {
                val dpm = remember { context.getSystemService(DevicePolicyManager::class.java) }
                Text("Profile owner: ${dpm.isProfileOwnerApp(context.packageName)}")
            }
        }
    }
}

private fun fileTest(context: Context) {
    runCatching {
        val dir = File(context.cacheDir, "bridge").apply { mkdirs() }
        val f = File(dir, "test.txt").apply { writeText("hello ${System.currentTimeMillis()}") }
        val uri = FileProvider.getUriForFile(context, "io.cloak.os.fileprovider", f)
        Commands.send(context, Commands.FILE_TEST, "test.txt", listOf(uri)).getOrThrow()
    }.onFailure { Log.e(TAG, "file test failed", it) }
}