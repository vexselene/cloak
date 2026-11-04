package io.cloak.os.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.cloak.os.bridge.SpaceState
import io.cloak.os.bridge.SpaceStateReader
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberUpdatedState
import android.content.pm.LauncherApps
import android.os.UserHandle
import io.cloak.os.apps.SpaceApps

private const val SAFETY_POLL_MS = 3_000L

internal tailrec fun Context.findActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("no activity")
}

@Composable
fun rememberSpaceState(refreshKey: Int = 0): State<SpaceState> {
    val context = LocalContext.current
    val lifecycle = remember(context) { context.findActivity().lifecycle }
    val state = remember { mutableStateOf(SpaceStateReader.read(context)) }
    var started by remember {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    state.value = SpaceStateReader.read(context)
                    started = true
                }
                Lifecycle.Event.ON_STOP -> started = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    DisposableEffect(started) {
        if (!started) return@DisposableEffect onDispose { }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                state.value = SpaceStateReader.read(context)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(started) {
        if (started) {
            while (true) {
                delay(SAFETY_POLL_MS)
                state.value = SpaceStateReader.read(context)
            }
        }
    }
    LaunchedEffect(refreshKey) { state.value = SpaceStateReader.read(context) }
    return state
}

@Composable
fun OnStart(block: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = remember(context) { context.findActivity().lifecycle }
    val current by rememberUpdatedState(block)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) current()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

@Composable
fun OnSpacePackagesChanged(block: () -> Unit) {
    val context = LocalContext.current
    val current by rememberUpdatedState(block)
    DisposableEffect(Unit) {
        val la = context.getSystemService(LauncherApps::class.java)
        fun changed(packageName: String, user: UserHandle) {
            if (packageName != context.packageName && user == SpaceApps.profile(context)) current()
        }
        val callback = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName, user)
            override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName, user)
            override fun onPackageChanged(packageName: String, user: UserHandle) = Unit
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = Unit
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = Unit
        }
        la.registerCallback(callback)
        onDispose { la.unregisterCallback(callback) }
    }
}