package io.cloak.os.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.cloak.os.R
import io.cloak.os.transfer.Conflict
import io.cloak.os.transfer.ConflictAction
import io.cloak.os.transfer.ConflictAnswer
import io.cloak.os.transfer.TransferFormat
import io.cloak.os.transfer.TransferHub

private class LastConflict {
    var value: Conflict? = null
}

/** Slides up from the bottom while a transfer is paused on a clash, and slides away once answered. */
@Composable
fun ConflictCardHost(modifier: Modifier = Modifier) {
    val state by TransferHub.state.collectAsState()
    val conflict = state?.conflict
    // The last clash is kept so the card still has content while it slides out.
    val last = remember { LastConflict() }
    if (conflict != null) last.value = conflict
    AnimatedVisibility(
        visible = conflict != null,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        last.value?.let { ConflictCard(it) { answer -> TransferHub.answer(answer) } }
    }
}

@Composable
private fun ConflictCard(conflict: Conflict, onAnswer: (ConflictAnswer) -> Unit) {
    var applyAll by remember(conflict.id) { mutableStateOf(false) }
    fun answer(action: ConflictAction) = onAnswer(ConflictAnswer(conflict.id, action, applyAll))

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.conflict_title), style = MaterialTheme.typography.titleMedium)
            Text(
                conflict.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (conflict.folder.isNotEmpty()) {
                Text(
                    stringResource(R.string.conflict_in_folder, conflict.folder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                stringResource(
                    R.string.conflict_sizes,
                    sizeText(conflict.newSize),
                    sizeText(conflict.existingSize)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (conflict.move) {
                Text(
                    stringResource(R.string.conflict_move_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = applyAll, role = Role.Checkbox, onValueChange = { applyAll = it }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = applyAll, onCheckedChange = null)
                Text(stringResource(R.string.conflict_apply_all), Modifier.padding(start = 8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { answer(ConflictAction.REPLACE) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text(stringResource(R.string.conflict_replace), maxLines = 1) }
                OutlinedButton(
                    onClick = { answer(ConflictAction.RENAME) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text(stringResource(R.string.conflict_keep_both), maxLines = 1) }
                OutlinedButton(
                    onClick = { answer(ConflictAction.SKIP) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text(stringResource(R.string.conflict_skip), maxLines = 1) }
            }
        }
    }
}

@Composable
private fun sizeText(n: Long): String =
    if (n < 0) stringResource(R.string.conflict_size_unknown) else TransferFormat.bytes(n)