package io.cloak.os.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.cloak.os.R
import io.cloak.os.apps.SpaceApps
import io.cloak.os.bridge.ApkExporter
import io.cloak.os.bridge.Commands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SecondSpace/AddApps"
private const val PLAY_STORE = "com.android.vending"
private const val ADD_TIMEOUT_MS = 90_000L

private data class AppEntry(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val icon: ImageBitmap
)

private fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map {
            AppEntry(
                label = it.loadLabel(pm).toString(),
                packageName = it.activityInfo.packageName,
                isSystem = (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                icon = it.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            )
        }
        .sortedBy { it.label.lowercase() }
}

private suspend fun addToSpace(context: Context, app: AppEntry): Result<Unit> {
    if (app.isSystem) return Commands.send(context, Commands.ENABLE_SYSTEM, app.packageName)
    val uris = withContext(Dispatchers.IO) { ApkExporter.export(context, app.packageName) }
        .getOrElse {
            Log.e(TAG, "export failed", it)
            return Result.failure(it)
        }
    return Commands.send(context, Commands.CLONE, app.packageName, uris)
}

@Composable
fun AddAppsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spaceApps by rememberSpaceApps()
    val inSpace = remember(spaceApps) { spaceApps.map { it.packageName }.toSet() }
    val entries by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.Default) { loadLaunchableApps(context) }
    }
    var query by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(inSpace) {
        val p = pending
        if (p != null && p in inSpace) pending = null
    }

    Column(modifier.imePadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_apps)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
        )
        Spacer(Modifier.height(8.dp))
        val list = entries
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val shown = remember(list, query) {
            list.filter { it.label.contains(query.trim(), ignoreCase = true) }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (query.isBlank()) {
                item(key = "play_store") {
                    PlayStoreRow {
                        val target = spaceApps.firstOrNull { it.packageName == PLAY_STORE }
                        if (target != null) {
                            SpaceApps.launch(context, target).onFailure { Log.e(TAG, "play store launch failed", it) }
                        } else {
                            Commands.send(context, Commands.ENABLE_SYSTEM, PLAY_STORE)
                            Toast.makeText(context, R.string.play_store_preparing, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            items(shown, key = { it.packageName }) { app ->
                val added = app.packageName in inSpace
                val busy = pending == app.packageName
                val failedMessage = stringResource(R.string.add_failed, app.label)
                AppRow(
                    app = app,
                    added = added,
                    busy = busy,
                    enabled = !added && pending == null,
                    onClick = {
                        pending = app.packageName
                        scope.launch {
                            addToSpace(context, app).onFailure {
                                pending = null
                                Toast.makeText(context, failedMessage, Toast.LENGTH_SHORT).show()
                            }
                        }
                        scope.launch {
                            delay(ADD_TIMEOUT_MS)
                            if (pending == app.packageName) pending = null
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PlayStoreRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.ShoppingCart,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.get_from_play_store))
            Text(
                stringResource(R.string.get_from_play_store_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, added: Boolean, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (app.isSystem) {
                Text(
                    stringResource(R.string.system_app),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        when {
            added -> Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.added),
                tint = MaterialTheme.colorScheme.primary
            )
            busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else -> Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.add),
                tint = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}