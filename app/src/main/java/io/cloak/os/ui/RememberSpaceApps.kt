package io.cloak.os.ui

import android.content.pm.LauncherApps
import android.os.UserHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.cloak.os.apps.SpaceApp
import io.cloak.os.apps.SpaceApps

@Composable
fun rememberSpaceApps(): State<List<SpaceApp>> {
    val context = LocalContext.current
    val apps = remember { mutableStateOf(SpaceApps.list(context)) }
    DisposableEffect(Unit) {
        val la = context.getSystemService(LauncherApps::class.java)
        val refresh = { apps.value = SpaceApps.list(context) }
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
    return apps
}