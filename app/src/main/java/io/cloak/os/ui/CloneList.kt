package io.cloak.os.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.cloak.os.bridge.Commands

private data class AppEntry(val label: String, val packageName: String)

private fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun CloneList(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val apps = remember { loadLaunchableApps(context) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(apps, key = { it.packageName }) { app ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(app.label, Modifier.weight(1f))
                Button(onClick = { Commands.send(context, Commands.CLONE, app.packageName) }) {
                    Text("Clone")
                }
            }
        }
    }
}