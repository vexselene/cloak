package io.cloak.os.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.cloak.os.bridge.ApkExporter
import io.cloak.os.bridge.Commands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AppEntry(val label: String, val packageName: String, val isSystem: Boolean)

private fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map {
            AppEntry(
                it.loadLabel(pm).toString(),
                it.activityInfo.packageName,
                (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

private suspend fun cloneApp(context: Context, app: AppEntry) {
    if (app.isSystem) {
        Commands.send(context, Commands.ENABLE_SYSTEM, app.packageName)
        return
    }
    val exported = withContext(Dispatchers.IO) { ApkExporter.export(context, app.packageName) }
    exported
        .onSuccess { Commands.send(context, Commands.CLONE, app.packageName, it) }
        .onFailure {
            Log.e("SecondSpace/Main", "export failed", it)
            Toast.makeText(context, "Could not read ${app.label}", Toast.LENGTH_SHORT).show()
        }
}

@Composable
fun CloneList(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps = remember { loadLaunchableApps(context) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(apps, key = { it.packageName }) { app ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(app.label + if (app.isSystem) " (system)" else "", Modifier.weight(1f))
                Button(onClick = { scope.launch { cloneApp(context, app) } }) { Text("Clone") }
            }
        }
    }
}