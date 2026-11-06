package io.cloak.os.ui

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.cloak.os.R
import io.cloak.os.bridge.Commands
import io.cloak.os.bridge.LockSettings
import io.cloak.os.bridge.PrivacySettings
import io.cloak.os.bridge.SpaceMaintenance
import io.cloak.os.bridge.TransferSettings
import io.cloak.os.common.Logx
import io.cloak.os.transfer.FolderPicker

private const val TAG = "SecondSpace/Settings"

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onOpenRecovery: () -> Unit = {}) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(PrivacySettings.load(context)) }
    var lock by remember { mutableStateOf(LockSettings.load(context)) }
    var transfer by remember { mutableStateOf(TransferSettings.load(context)) }
    var confirmDelete by remember { mutableStateOf(false) }

    val reachableMsg = stringResource(R.string.maint_reachable)
    val unreachableMsg = stringResource(R.string.maint_unreachable)
    val repairSentMsg = stringResource(R.string.maint_repair_sent)
    val repairFailedMsg = stringResource(R.string.maint_repair_failed)
    val sendFailedMsg = stringResource(R.string.privacy_send_failed)
    val pickerSentMsg = stringResource(R.string.picker_requested)
    val pickerNoneMsg = stringResource(R.string.picker_none)

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

    fun updateShare(on: Boolean) {
        val old = transfer
        transfer = TransferSettings(on)
        TransferSettings.save(context, transfer)
        Commands.send(context, Commands.SET_SHARE, transfer.encode()).onFailure {
            Logx.e(TAG, "send share setting failed", it)
            transfer = old
            TransferSettings.save(context, old)
            Toast.makeText(context, sendFailedMsg, Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        SettingsGroup(R.string.privacy_title) {
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
        }

        SettingsGroup(R.string.lock_title) {
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
        }

        SettingsGroup(R.string.transfer_settings_title) {
            SettingToggle(R.string.share_out, R.string.share_out_desc, transfer.shareFromSpace) { updateShare(it) }
            Text(
                stringResource(R.string.picker_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CompactButton(R.string.picker_enable, onClick = {
                val ok = FolderPicker.enableInSpace(context)
                Toast.makeText(context, if (ok) pickerSentMsg else pickerNoneMsg, Toast.LENGTH_LONG).show()
            })
        }

        SettingsGroup(R.string.settings_group_lock) {
            CompactButton(
                R.string.set_lock,
                onClick = { Commands.send(context, Commands.SET_LOCK) },
                filled = true
            )
        }

        SettingsGroup(R.string.maint_title) {
            Text(
                stringResource(R.string.maint_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactButton(R.string.maint_check, onClick = {
                    val ok = SpaceMaintenance.check(context).isSuccess
                    Toast.makeText(context, if (ok) reachableMsg else unreachableMsg, Toast.LENGTH_LONG).show()
                })
                CompactButton(R.string.maint_repair, onClick = {
                    val msg = if (SpaceMaintenance.repair(context).isSuccess) repairSentMsg else repairFailedMsg
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                })
            }
        }

        SettingsGroup(R.string.recovery_title) {
            Text(
                stringResource(R.string.recovery_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CompactButton(R.string.recovery_open, onClick = onOpenRecovery)
        }

        SettingsGroup(R.string.settings_group_danger) {
            CompactButton(R.string.delete_space, onClick = { confirmDelete = true }, danger = true)
        }
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
private fun SettingsGroup(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content
            )
        }
    }
}

@Composable
private fun CompactButton(
    @StringRes label: Int,
    onClick: () -> Unit,
    filled: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(18.dp)
    val padding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
    val modifier = Modifier.height(36.dp)
    if (filled) {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            contentPadding = padding
        ) { Text(stringResource(label), style = MaterialTheme.typography.labelMedium) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            colors = if (danger) {
                ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.outlinedButtonColors()
            },
            contentPadding = padding
        ) { Text(stringResource(label), style = MaterialTheme.typography.labelMedium) }
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