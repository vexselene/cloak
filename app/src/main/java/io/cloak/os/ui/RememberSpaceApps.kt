package io.cloak.os.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import io.cloak.os.apps.SpaceApp
import io.cloak.os.apps.SpaceApps
import io.cloak.os.common.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private const val TAG = "SecondSpace/Apps"

/** One live list of the apps in the space, shared by every screen. */
internal object SpaceAppsHolder {
    val state: MutableState<List<SpaceApp>> = mutableStateOf(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = Channel<Context>(Channel.CONFLATED)
    private var callback: LauncherApps.Callback? = null

    init {
        scope.launch {
            for (context in requests) {
                runCatching { SpaceApps.list(context) }
                    .onSuccess { state.value = it }
                    .onFailure { Logx.e(TAG, "listing the apps failed", it) }
            }
        }
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        register(app)
        ask(app)
    }

    private fun ask(context: Context) {
        requests.trySend(context)
    }

    @Synchronized
    private fun register(context: Context) {
        if (callback != null) return
        val la = context.getSystemService(LauncherApps::class.java)
        val cb = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) { ask(context) }
            override fun onPackageAdded(packageName: String, user: UserHandle) { ask(context) }
            override fun onPackageChanged(packageName: String, user: UserHandle) { ask(context) }
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { ask(context) }
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { ask(context) }
            override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) { ask(context) }
            override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) { ask(context) }
        }
        // registerCallback(cb) builds a Handler on the calling thread, which crashes off the main thread.
        runCatching { la.registerCallback(cb, Handler(Looper.getMainLooper())) }
            .onSuccess { callback = cb }
            .onFailure { Logx.e(TAG, "could not watch the space's apps", it) }
    }
}

@Composable
fun rememberSpaceApps(): State<List<SpaceApp>> {
    val context = LocalContext.current
    val pulse by AppListRefresh.pulse.collectAsState()
    // Runs on first display and again on every pulse from the unhide loop.
    LaunchedEffect(pulse) { SpaceAppsHolder.refresh(context) }
    return SpaceAppsHolder.state
}