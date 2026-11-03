package io.cloak.os.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.PersistableBundle
import io.cloak.os.common.Logx
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.cloak.os.R
import io.cloak.os.admin.SpaceAdminReceiver
import io.cloak.os.bridge.Auth
import io.cloak.os.bridge.Commands
import io.cloak.os.bridge.SpaceState
import io.cloak.os.bridge.SpaceStateReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshotFlow
import io.cloak.os.bridge.LockSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "SecondSpace/Home"

private enum class Page { GRID, ADD, SETTINGS }

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val dpm = remember { context.getSystemService(DevicePolicyManager::class.java) }
    val scope = rememberCoroutineScope()
    val state by rememberSpaceState()
    var page by remember { mutableStateOf(Page.GRID) }

    LaunchedEffect(state) { if (state != SpaceState.UNLOCKED) page = Page.GRID }

    val spaceApps by rememberSpaceApps()
    var lastSession by remember { mutableLongStateOf(0L) }
    var opening by remember {
        mutableStateOf(state == SpaceState.UNLOCKED && LockSettings.load(context).hideApps)
    }

    fun sendSession() {
        if (!LockSettings.load(context).hideApps) return
        val now = System.currentTimeMillis()
        if (now - lastSession < 2_000L) return
        lastSession = now
        Commands.send(context, Commands.SESSION).onFailure { Logx.e(TAG, "session failed", it) }
    }

    OnStart {
        when (SpaceStateReader.read(context)) {
            SpaceState.UNLOCKED -> sendSession()
            SpaceState.PAUSED, SpaceState.LOCKED ->
                Commands.send(context, Commands.UNLOCK).onFailure { Logx.e(TAG, "auto unlock failed", it) }
            SpaceState.NONE -> Unit
        }
    }

    LaunchedEffect(state) {
        if (state == SpaceState.UNLOCKED && LockSettings.load(context).hideApps) {
            opening = true
            sendSession()
            withTimeoutOrNull(2_000L) { snapshotFlow { spaceApps.isNotEmpty() }.first { it } }
            opening = false
        } else {
            opening = false
        }
    }

    BackHandler(enabled = page != Page.GRID) { page = Page.GRID }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                delay(3000)
                repeat(30) { i ->
                    val st = SpaceStateReader.read(context)
                    val r = if (st != SpaceState.NONE) Commands.send(context, Commands.SET_LOCK) else null
                    Logx.d(TAG, "set_lock attempt $i state=$st result=$r")
                    if (r?.isSuccess == true) return@launch
                    delay(1000)
                }
            }
        }
    }

    when (state) {
        SpaceState.NONE -> WelcomeScreen(
            allowed = dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE),
            onCreate = {
                val secret = Auth.generateSecret()
                Auth.saveSecret(context, secret)
                launcher.launch(
                    Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
                        .putExtra(
                            DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                            SpaceAdminReceiver.component(context)
                        )
                        .putExtra(
                            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                            PersistableBundle().apply { putString(Auth.BUNDLE_SECRET, secret) }
                        )
                )
            }
        )
        SpaceState.PAUSED, SpaceState.LOCKED -> LockedScreen(
            paused = state == SpaceState.PAUSED,
            onUnlock = {
                Commands.send(context, Commands.UNLOCK).onFailure { Logx.e(TAG, "unlock failed", it) }
            }
        )
        SpaceState.UNLOCKED -> if (opening) OpeningScreen() else when (page) {
            Page.GRID -> SpaceHome(
                onLock = { Commands.send(context, Commands.LOCK_NOW) },
                onAddApps = { page = Page.ADD },
                onSettings = { page = Page.SETTINGS }
            )
            Page.ADD -> SubPage(stringResource(R.string.add_apps), onBack = { page = Page.GRID }) {
                AddAppsScreen(it)
            }
            Page.SETTINGS -> SubPage(stringResource(R.string.settings), onBack = { page = Page.GRID }) {
                SettingsScreen(it)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpaceHome(onLock: () -> Unit, onAddApps: () -> Unit, onSettings: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.space_name)) },
                actions = {
                    IconButton(onClick = onLock) {
                        Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.lock))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_apps)) },
                                onClick = { menuOpen = false; onAddApps() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                onClick = { menuOpen = false; onSettings() }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { inner ->
        SpaceGrid(Modifier.fillMaxSize().padding(inner))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { inner ->
        content(Modifier.padding(inner).padding(horizontal = 16.dp, vertical = 8.dp))
    }
}