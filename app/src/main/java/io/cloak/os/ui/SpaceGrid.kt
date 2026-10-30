package io.cloak.os.ui

import android.content.pm.LauncherApps
import android.os.UserHandle
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.cloak.os.apps.SpaceApps
import io.cloak.os.bridge.Commands

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpaceGrid(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(SpaceApps.list(context)) }

    DisposableEffect(Unit) {
        val la = context.getSystemService(LauncherApps::class.java)
        val refresh = { apps = SpaceApps.list(context) }
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()
            override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
            override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
            override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = refresh()
            override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = refresh()
        }
        la.registerCallback(callback)
        onDispose { la.unregisterCallback(callback) }
    }

    if (apps.isEmpty()) {
        Text("No apps in Second Space yet. Tap Add apps.", modifier)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(88.dp),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(apps, key = { it.packageName }) { app ->
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                Column(
                    Modifier
                        .combinedClickable(
                            onClick = {
                                SpaceApps.launch(context, app)
                                    .onFailure { Log.e("SecondSpace/Grid", "launch failed", it) }
                            },
                            onLongClick = { menuOpen = true }
                        )
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val bitmap = remember(app.packageName) { app.icon.toBitmap(128, 128).asImageBitmap() }
                    Image(
                        bitmap = bitmap,
                        contentDescription = app.label,
                        modifier = Modifier.size(56.dp),
                        alpha = if (app.suspended) 0.4f else 1f
                    )
                    Text(
                        app.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (app.suspended) "Unfreeze" else "Freeze") },
                        onClick = {
                            menuOpen = false
                            Commands.send(
                                context,
                                if (app.suspended) Commands.UNFREEZE else Commands.FREEZE,
                                app.packageName
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Remove from space") },
                        onClick = {
                            menuOpen = false
                            Commands.send(context, Commands.UNINSTALL, app.packageName)
                        }
                    )
                }
            }
        }
    }
}