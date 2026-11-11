package io.cloak.os.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.PersistableBundle
import io.cloak.os.common.Logx
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.mutableIntStateOf
import io.cloak.os.bridge.RecoveryStore
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.collectAsState
import io.cloak.os.bridge.SpaceEvents

private const val TAG = "SecondSpace/Home"
private const val UNLOCK_SETTLE_MS = 100L
private const val AUTO_UNLOCK_GAP_MS = 20_000L

private enum class Page { GRID, ADD, SETTINGS, RECOVERY, RESTORE, TRANSFER }

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val dpm = remember { context.getSystemService(DevicePolicyManager::class.java) }
    val scope = rememberCoroutineScope()
    val reconciler = rememberTransferReconciler()
    var checkTick by remember { mutableIntStateOf(0) }
    val pulse by SpaceEvents.pulse.collectAsState()
    LaunchedEffect(pulse) { if (pulse > 0) checkTick++ }
    val state by rememberSpaceState(checkTick)
    var page by remember { mutableStateOf(Page.GRID) }
    var restoring by remember { mutableStateOf(false) }

    LaunchedEffect(state) { if (state != SpaceState.UNLOCKED) page = Page.GRID }
    LaunchedEffect(state) { if (state != SpaceState.UNPAIRED) restoring = false }

    val spaceApps by rememberSpaceApps()
    var lastSession by remember { mutableLongStateOf(0L) }
    var opening by remember {
        mutableStateOf(state == SpaceState.UNLOCKED && LockSettings.load(context).hideApps)
    }

    var appsChanged by remember { mutableStateOf(false) }
    OnSpacePackagesChanged { appsChanged = true }

    fun sendSession() {
        if (!LockSettings.load(context).hideApps) return
        val now = System.currentTimeMillis()
        if (now - lastSession < 2_000L) return
        lastSession = now
        val refresh = appsChanged
        appsChanged = false
        Commands.send(context, Commands.SESSION, if (refresh) "r" else "").onFailure {
            if (refresh) appsChanged = true
            Logx.e(TAG, "session failed", it)
        }
    }

    val unlockLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        Logx.d(TAG, "unlock result=${r.resultCode}")
    }
    fun requestUnlock() {
        runCatching { unlockLauncher.launch(Commands.build(context, Commands.UNLOCK)) }
            .onFailure { Logx.e(TAG, "unlock failed", it) }
    }
    OnStart {
        when (SpaceStateReader.read(context)) {
            SpaceState.UNLOCKED -> {
                sendSession()
                // Later, so it cannot race the session command, which must reach the space first.
                scope.launch {
                    delay(2_500L)
                    reconciler.trigger()
                }
            }
            SpaceState.PAUSED, SpaceState.LOCKED,
            SpaceState.NONE, SpaceState.UNPAIRED, SpaceState.FOREIGN -> Unit
        }
    }

    var lastAutoUnlock by remember { mutableLongStateOf(0L) }
    val hostLifecycle = remember(context) { context.findActivity().lifecycle }

    OnResume {
        val s = SpaceStateReader.read(context)
        val now = System.currentTimeMillis()
        if ((s == SpaceState.PAUSED || s == SpaceState.LOCKED) && now - lastAutoUnlock > AUTO_UNLOCK_GAP_MS) {
            lastAutoUnlock = now
            scope.launch {
                withFrameNanos { }
                delay(UNLOCK_SETTLE_MS)
                val again = SpaceStateReader.read(context)
                val stillHere = hostLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                if (stillHere && (again == SpaceState.PAUSED || again == SpaceState.LOCKED)) requestUnlock()
            }
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

    LaunchedEffect(state) {
        if (state == SpaceState.NONE || state == SpaceState.FOREIGN) {
            RecoveryStore.setEnabledHint(context, false)
        }
    }

    BackHandler(enabled = page != Page.GRID || restoring) {
        when {
            restoring -> restoring = false
            page == Page.RESTORE -> page = Page.RECOVERY
            page == Page.RECOVERY -> page = Page.SETTINGS
            else -> page = Page.GRID
        }
    }

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
        SpaceState.UNPAIRED -> if (restoring) {
            SubPage(stringResource(R.string.restore_title), onBack = { restoring = false }) {
                RecoveryRestoreScreen(it, onDone = { restoring = false; checkTick++ })
            }
        } else {
            UnpairedScreen(onRecover = { restoring = true })
        }
        SpaceState.FOREIGN -> ForeignScreen(onCheckAgain = { checkTick++ })
        SpaceState.PAUSED, SpaceState.LOCKED -> LockedScreen(
            paused = state == SpaceState.PAUSED,
            onUnlock = {
                requestUnlock()
            }
        )
        SpaceState.UNLOCKED -> if (opening) OpeningScreen() else when (page) {
            Page.GRID -> SpaceHome(
                onLock = { Commands.send(context, Commands.LOCK_NOW) },
                onAddApps = { page = Page.ADD },
                onTransfer = { page = Page.TRANSFER },
                onSettings = { page = Page.SETTINGS }
            )
            Page.ADD -> SubPage(stringResource(R.string.add_apps), onBack = { page = Page.GRID }) {
                AddAppsScreen(it)
            }
            Page.SETTINGS -> SubPage(stringResource(R.string.settings), onBack = { page = Page.GRID }) {
                SettingsScreen(it, onOpenRecovery = { page = Page.RECOVERY })
            }
            Page.RECOVERY -> SubPage(stringResource(R.string.recovery_title), onBack = { page = Page.SETTINGS }) {
                RecoverySetupScreen(
                    modifier = it,
                    onRestore = { page = Page.RESTORE },
                    onDone = { page = Page.SETTINGS }
                )
            }
            Page.RESTORE -> SubPage(stringResource(R.string.restore_title), onBack = { page = Page.RECOVERY }) {
                RecoveryRestoreScreen(it, onDone = { page = Page.SETTINGS; checkTick++ })
            }
            Page.TRANSFER -> SubPage(stringResource(R.string.transfer_menu), onBack = { page = Page.GRID }) {
                TransferScreen(it, onCheck = { reconciler.checkNow() })
            }
        }
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
        },
        bottomBar = { TransferCardHost() }
    ) { inner ->
        content(Modifier.padding(inner).padding(horizontal = 16.dp, vertical = 8.dp))
    }
}