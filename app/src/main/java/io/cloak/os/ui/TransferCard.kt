package io.cloak.os.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.cloak.os.R
import io.cloak.os.transfer.Phase
import io.cloak.os.transfer.TransferFormat
import io.cloak.os.transfer.TransferHub
import io.cloak.os.transfer.TransferState
import io.cloak.os.transfer.transferTitle

@Composable
fun TransferCardHost(modifier: Modifier = Modifier) {
    val state by TransferHub.state.collectAsState()
    val s = state ?: return
    TransferCard(
        s,
        onCancel = { TransferHub.cancel() },
        onDismiss = { TransferHub.clear() },
        modifier = modifier.padding(12.dp)
    )
}

@Composable
fun TransferCard(
    state: TransferState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (state.phase == Phase.COPYING) {
                    stringResource(R.string.transfer_copying, state.filesDone, state.filesTotal)
                } else {
                    stringResource(transferTitle(state))
                },
                style = MaterialTheme.typography.titleMedium
            )
            if (state.running) {
                val f = state.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (state.bytesTotal > 0L) {
                    Text(
                        stringResource(
                            R.string.transfer_bytes,
                            TransferFormat.bytes(state.bytesDone),
                            TransferFormat.bytes(state.bytesTotal)
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                state.recent.forEach {
                    Text(
                        "✓ $it", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.current.isNotEmpty()) {
                    Text(state.current, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(
                    stringResource(
                        if (state.settle) R.string.transfer_settle_summary else R.string.transfer_summary,
                        state.copied, state.skipped, state.failed
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (state.needsSettle) {
                    Text(
                        stringResource(R.string.transfer_move_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (state.interrupted) {
                    Text(
                        stringResource(R.string.transfer_interrupted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("", Modifier.weight(1f))
                if (state.running) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                } else {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.transfer_dismiss)) }
                }
            }
        }
    }
}