package io.cloak.os.ui

import android.content.pm.LauncherApps
import android.os.UserHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import io.cloak.os.apps.SpaceApp
import io.cloak.os.apps.SpaceApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context

@Composable
fun rememberSpaceApps(): State<List<SpaceApp>> {
    val context = LocalContext.current
    val apps = SpaceAppsHolder.state
    val pulse by AppListRefresh.pulse.collectAsState()
    LaunchedEffect(pulse) {
        if (pulse > 0) SpaceAppsHolder.refresh(context)
    }
    LaunchedEffect(Unit) {
        SpaceAppsHolder.refresh(context)
    }
    return apps
}

internal object SpaceAppsHolder {
    val state = mutableStateOf<List<SpaceApp>>(emptyList())

    @Volatile private var registered = false

    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            state.value = withContext(Dispatchers.Default) { SpaceApps.list(app) }
            register(app)
        }
    }

    private fun register(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val la = context.getSystemService(LauncherApps::class.java)
            val callback = object : LauncherApps.Callback() {
                override fun onPackageRemoved(packageName: String, user: UserHandle) = schedule(context)
                override fun onPackageAdded(packageName: String, user: UserHandle) = schedule(context)
                override fun onPackageChanged(packageName: String, user: UserHandle) = schedule(context)
                override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = schedule(context)
                override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = schedule(context)
                override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = schedule(context)
                override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = schedule(context)
            }
            la.registerCallback(callback)
            registered = true
        }
    }

    private fun schedule(context: Context) {
        scope.launch(Dispatchers.Default) {
            state.value = SpaceApps.list(context)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}