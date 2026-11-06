package io.cloak.os.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import io.cloak.os.R
import io.cloak.os.bridge.Commands
import io.cloak.os.common.Logx
import io.cloak.os.transfer.MoveLedger
import io.cloak.os.transfer.Pickers
import io.cloak.os.transfer.ReportStore
import io.cloak.os.transfer.TransferFlows
import io.cloak.os.transfer.TransferHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SecondSpace/Transfer"

@Composable
fun TransferScreen(modifier: Modifier = Modifier, onCheck: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by TransferHub.state.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }
    val waiting = remember { MoveLedger.all(context).size + ReportStore.undelivered(context).size }
    val locked = scanning || state?.running == true

    fun toSpace(uris: List<Uri>) {
        scope.launch {
            scanning = true
            notice = null
            val prepared = withContext(Dispatchers.IO) { runCatching { TransferFlows.prepare(context, uris) } }
            scanning = false
            prepared
                .onSuccess { entry ->
                    runCatching { context.startActivity(TransferFlows.sendIntent(context, entry)) }
                        .onFailure { error ->
                            Logx.e(TAG, "send to space failed", error)
                            MoveLedger.remove(context, entry.id)
                            notice = R.string.transfer_send_failed
                        }
                }
                .onFailure { error ->
                    Logx.e(TAG, "scan failed", error)
                    notice = R.string.transfer_scan_failed
                }
        }
    }

    val pickFiles = rememberLauncherForActivityResult(Pickers.Files()) { uris ->
        if (uris.isNotEmpty()) toSpace(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(Pickers.Folder()) { uri ->
        if (uri != null) toSpace(listOf(uri))
    }

    fun pickHere(files: Boolean) {
        runCatching { if (files) pickFiles.launch(Unit) else pickFolder.launch(Unit) }
            .onFailure { notice = R.string.transfer_no_picker_personal }
    }

    fun pickInSpace(kind: String) {
        Commands.send(context, Commands.TRANSFER_PICK, kind).onFailure {
            Logx.e(TAG, "could not open the space picker", it)
            notice = R.string.transfer_send_failed
        }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.transfer_page_intro), style = MaterialTheme.typography.bodyMedium)
        Direction(R.string.transfer_to_space, R.string.transfer_to_space_desc, !locked,
            onFiles = { pickHere(true) }, onFolder = { pickHere(false) })
        HorizontalDivider()
        Direction(R.string.transfer_from_space, R.string.transfer_from_space_desc, !locked,
            onFiles = { pickInSpace("files") }, onFolder = { pickInSpace("folder") })
        HorizontalDivider()
        if (scanning) Text(stringResource(R.string.transfer_scanning))
        notice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        Text(
            stringResource(R.string.transfer_move_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (waiting > 0) Text(stringResource(R.string.transfer_waiting, waiting))
        OutlinedButton(onClick = onCheck, enabled = !locked, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.transfer_check))
        }
        Text(
            stringResource(R.string.transfer_picker_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Direction(
    @StringRes title: Int,
    @StringRes description: Int,
    enabled: Boolean,
    onFiles: () -> Unit,
    onFolder: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onFiles, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.transfer_files))
            }
            Button(onClick = onFolder, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.transfer_folder))
            }
        }
    }
}