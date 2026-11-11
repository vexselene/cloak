package io.cloak.os.ui

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.cloak.os.R
import io.cloak.os.apps.SpaceApps
import io.cloak.os.bridge.Commands
import io.cloak.os.bridge.LockSettings
import io.cloak.os.bridge.RecoveryStore
import io.cloak.os.common.Logx
import kotlinx.coroutines.delay

private const val TAG = "SecondSpace/Onboarding"
private const val PLAY_STORE = "com.android.vending"
private const val CHECK_GAP_MS = 2_500L
private const val MAX_AUTO_RETRIES = 5

@Composable
fun OnboardingFlow(step: Int, onStep: (Int) -> Unit, onFinish: () -> Unit, onOpenRecovery: () -> Unit) {
    val s = OnboardingRules.clamp(step)
    when (s) {
        0 -> LockStep { onStep(OnboardingRules.next(s)) }
        1 -> StoreStep { onStep(OnboardingRules.next(s)) }
        2 -> AppsStep { onStep(OnboardingRules.next(s)) }
        else -> ProtectStep(onOpenRecovery, onFinish)
    }
}

@Composable
private fun StepPage(
    index: Int,
    @StringRes title: Int,
    @StringRes body: Int,
    scrollable: Boolean = true,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(OnboardingRules.STEP_COUNT) { i ->
                Box(
                    Modifier
                        .size(width = if (i == index) 22.dp else 8.dp, height = 8.dp)
                        .clip(CircleShape)
                        .background(if (i <= index) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.35f))
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.onboarding_step, index + 1, OnboardingRules.STEP_COUNT),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
        }
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

private enum class LockStatus { CHECKING, SEPARATE, NOT_SET, UNREACHABLE }

@Composable
private fun LockStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(LockStatus.CHECKING) }
    var failures by remember { mutableIntStateOf(0) }
    var lastCheck by remember { mutableLongStateOf(0L) }
    val notReadyMsg = stringResource(R.string.onboarding_not_ready)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            failures = 0
            val separate = r.data?.getBooleanExtra(Commands.EXTRA_LOCK_SEPARATE, false) == true
            status = if (separate) LockStatus.SEPARATE else LockStatus.NOT_SET
        } else {
            failures++
            status = LockStatus.UNREACHABLE
        }
    }

    fun check(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        // Cloak resumes after every check, so without this gap a check would trigger another one.
        if (!force && now - lastCheck < CHECK_GAP_MS) return
        lastCheck = now
        status = LockStatus.CHECKING
        runCatching { launcher.launch(Commands.build(context, Commands.LOCK_STATUS)) }
            .onFailure {
                Logx.e(TAG, "lock check failed", it)
                failures++
                status = LockStatus.UNREACHABLE
            }
    }

    // Runs on first display and again when the user comes back from the lock screen.
    OnResume { check(force = false) }

    LaunchedEffect(status, failures) {
        if (status == LockStatus.UNREACHABLE && failures in 1..MAX_AUTO_RETRIES) {
            delay(3_000L)
            check(force = true)
        }
    }

    StepPage(
        index = 0,
        title = R.string.onboarding_lock_title,
        body = R.string.onboarding_lock_body,
        actions = {
            if (status == LockStatus.SEPARATE) {
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_continue))
                }
            } else {
                Button(
                    onClick = {
                        Commands.send(context, Commands.SET_LOCK).onFailure {
                            Toast.makeText(context, notReadyMsg, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.set_lock)) }
                OutlinedButton(
                    onClick = {
                        failures = 0
                        check(force = true)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.onboarding_check_again)) }
                TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            }
        }
    ) {
        val (text, color) = when (status) {
            LockStatus.CHECKING -> R.string.onboarding_lock_checking to MaterialTheme.colorScheme.onSurfaceVariant
            LockStatus.SEPARATE -> R.string.onboarding_lock_ok to MaterialTheme.colorScheme.primary
            LockStatus.NOT_SET -> R.string.onboarding_lock_not_set to MaterialTheme.colorScheme.onSurface
            LockStatus.UNREACHABLE ->
                (if (failures <= MAX_AUTO_RETRIES) R.string.onboarding_lock_waiting else R.string.onboarding_lock_unreachable) to
                        MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(stringResource(text), color = color, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.onboarding_lock_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StoreStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val apps by rememberSpaceApps()
    val store = apps.firstOrNull { it.packageName == PLAY_STORE }
    var preparing by remember { mutableStateOf(false) }
    val notReadyMsg = stringResource(R.string.onboarding_not_ready)
    LaunchedEffect(store) { if (store != null) preparing = false }

    StepPage(
        index = 1,
        title = R.string.onboarding_store_title,
        body = R.string.onboarding_store_body,
        actions = {
            Button(
                onClick = {
                    if (store != null) {
                        SpaceApps.launch(context, store).onFailure { Logx.e(TAG, "play store launch failed", it) }
                    } else {
                        preparing = true
                        Commands.send(context, Commands.ENABLE_SYSTEM, PLAY_STORE).onFailure {
                            preparing = false
                            Toast.makeText(context, notReadyMsg, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !preparing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        when {
                            store != null -> R.string.onboarding_store_open
                            preparing -> R.string.onboarding_store_preparing
                            else -> R.string.onboarding_store_prepare
                        }
                    )
                )
            }
            OutlinedButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_continue))
            }
        }
    ) {
        Text(
            stringResource(R.string.onboarding_store_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppsStep(onNext: () -> Unit) {
    StepPage(
        index = 2,
        title = R.string.onboarding_apps_title,
        body = R.string.onboarding_apps_body,
        scrollable = false,
        actions = {
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_continue))
            }
        }
    ) {
        AddAppsScreen(Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun ProtectStep(onOpenRecovery: () -> Unit, onFinish: () -> Unit) {
    val context = LocalContext.current
    var lock by remember { mutableStateOf(LockSettings.load(context)) }
    val recovery = remember { RecoveryStore.isEnabledHint(context) }
    val failedMsg = stringResource(R.string.privacy_send_failed)

    fun setHide(on: Boolean) {
        val old = lock
        lock = lock.copy(hideApps = on)
        LockSettings.save(context, lock)
        Commands.send(context, Commands.SET_LOCK_MODE, lock.encode()).onFailure {
            Logx.e(TAG, "send lock mode failed", it)
            lock = old
            LockSettings.save(context, old)
            Toast.makeText(context, failedMsg, Toast.LENGTH_LONG).show()
        }
    }

    StepPage(
        index = 3,
        title = R.string.onboarding_protect_title,
        body = R.string.onboarding_protect_body,
        actions = {
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_finish))
            }
        }
    ) {
        OptionCard {
            Text(stringResource(R.string.recovery_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.onboarding_recovery_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (recovery) {
                Text(
                    stringResource(R.string.onboarding_recovery_set),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(onClick = onOpenRecovery, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (recovery) R.string.recovery_replace else R.string.recovery_create))
            }
        }
        OptionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.lock_hide),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = lock.hideApps, onCheckedChange = { setHide(it) })
            }
            Text(
                stringResource(R.string.lock_hide_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (lock.hideApps && !recovery) {
                Text(
                    stringResource(R.string.onboarding_hide_needs_recovery),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun OptionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}