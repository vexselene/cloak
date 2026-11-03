package io.cloak.os.ui

import io.cloak.os.common.Logx
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.cloak.os.R
import io.cloak.os.bridge.Commands
import io.cloak.os.bridge.PrivacySettings
import android.widget.Toast
import io.cloak.os.bridge.SpaceMaintenance
import io.cloak.os.bridge.LockSettings
import androidx.compose.ui.draw.alpha

private const val TAG = "SecondSpace/Settings"

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val reachableMsg = stringResource(R.string.maint_reachable)
    val unreachableMsg = stringResource(R.string.maint_unreachable)
    val sendFailedMsg = stringResource(R.string.privacy_send_failed)
    val repairSentMsg = stringResource(R.string.maint_repair_sent)
    val repairFailedMsg = stringResource(R.string.maint_repair_failed)

    val context = LocalContext.current
    var settings by remember { mutableStateOf(PrivacySettings.load(context)) }
    var confirmDelete by remember { mutableStateOf(false) }

    var lock by remember { mutableStateOf(LockSettings.load(context)) }

    fun update(new: PrivacySettings) {
        val old = settings
        settings = new
        PrivacySettings.save(context, new)
        Commands.send(context, Commands.SET_PRIVACY, new.encode()).onFailure {
            Logx.e(TAG, "send privacy failed", it)
            settings = old
            PrivacySettings.save(context, old)
            Toast.makeText(context, sendFailedMsg, Toast.LENGTH_LONG).show()
        }
    }

    fun updateLock(new: LockSettings) {
        val old = lock
        lock = new
        LockSettings.save(context, new)
        Commands.send(context, Commands.SET_LOCK_MODE, new.encode()).onFailure {
            Logx.e(TAG, "send lock mode failed", it)
            lock = old
            LockSettings.save(context, old)
            Toast.makeText(context, sendFailedMsg, Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleMedium)
        SettingToggle(R.string.privacy_screenshots, R.string.privacy_screenshots_desc, settings.blockScreenshots) {
            update(settings.copy(blockScreenshots = it))
        }
        SettingToggle(R.string.privacy_notifications, R.string.privacy_notifications_desc, settings.hideNotificationContent) {
            update(settings.copy(hideNotificationContent = it))
        }
        SettingToggle(R.string.privacy_clipboard, R.string.privacy_clipboard_desc, settings.blockClipboard) {
            update(settings.copy(blockClipboard = it))
        }
        SettingToggle(R.string.privacy_contacts, R.string.privacy_contacts_desc, settings.blockContacts) {
            update(settings.copy(blockContacts = it))
        }
        HorizontalDivider()
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleMedium)
        SettingToggle(R.string.lock_hide, R.string.lock_hide_desc, lock.hideApps) { on ->
            updateLock(lock.copy(hideApps = on))
        }
        if (lock.hideApps) {
            SettingToggle(R.string.lock_boot, R.string.lock_boot_desc, lock.bootFix) { on ->
                updateLock(lock.copy(bootFix = on))
            }
            SettingToggle(R.string.lock_shutdown, R.string.lock_shutdown_desc, lock.hideOnShutdown) { on ->
                updateLock(lock.copy(hideOnShutdown = on))
            }
            SettingToggle(R.string.lock_hide_all, R.string.lock_hide_all_desc, false, enabled = false) { }
        }
        HorizontalDivider()
        Button(
            onClick = { Commands.send(context, Commands.SET_LOCK) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.set_lock)) }
        HorizontalDivider()
        Text(stringResource(R.string.maint_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.maint_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = {
                val ok = SpaceMaintenance.check(context).isSuccess
                Toast.makeText(context, if (ok) reachableMsg else unreachableMsg, Toast.LENGTH_LONG).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.maint_check)) }
        OutlinedButton(
            onClick = {
                val msg = if (SpaceMaintenance.repair(context).isSuccess) repairSentMsg else repairFailedMsg
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.maint_repair)) }
        HorizontalDivider()
        OutlinedButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) { Text(stringResource(R.string.delete_space)) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    Commands.send(context, Commands.DELETE_SPACE)
                        .onFailure { Logx.e(TAG, "send delete failed", it) }
                }) { Text(stringResource(R.string.delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun SettingToggle(
    @StringRes title: Int,
    @StringRes description: Int,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title))
            Text(
                stringResource(description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}