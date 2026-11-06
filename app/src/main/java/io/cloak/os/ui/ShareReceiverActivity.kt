package io.cloak.os.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.cloak.os.R
import io.cloak.os.common.Logx
import io.cloak.os.common.Role
import io.cloak.os.transfer.ConflictPolicy
import io.cloak.os.transfer.DocTree
import io.cloak.os.transfer.Grants
import io.cloak.os.transfer.Op
import io.cloak.os.transfer.ShareIntents
import io.cloak.os.transfer.TransferHub
import io.cloak.os.transfer.TransferId
import io.cloak.os.transfer.TransferJob
import io.cloak.os.transfer.TransferWork
import io.cloak.os.ui.theme.SpaceBackground
import io.cloak.os.ui.theme.SpaceTheme

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        // Only content URIs, and a sane number of them.
        val uris = ShareIntents.sharedUris(intent).filter { it.scheme == "content" }.take(MAX_ITEMS)
        val id = intent.getStringExtra(ShareIntents.EXTRA_TRANSFER_ID)?.takeIf { TransferId.isValid(it) }
        val inAppId = if (intent.action == ShareIntents.ACTION_TRANSFER_IN) id else null
        val role = Role.detect(this)
        Logx.d(TAG, "opened: action=${intent.action} items=${uris.size} inApp=${inAppId != null}")
        if (savedInstanceState == null) Grants.pruneForwarded(applicationContext)
        setContent {
            SpaceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().background(SpaceBackground),
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) { ReceiverScreen(uris, role, inAppId) { finish() } }
            }
        }
    }

    private companion object {
        const val TAG = "SecondSpace/ShareReceiver"
        const val MAX_ITEMS = 500
    }
}

@Composable
private fun ReceiverScreen(uris: List<Uri>, role: Role, inAppId: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val state by TransferHub.state.collectAsState()
    var policy by rememberSaveable { mutableStateOf(ConflictPolicy.RENAME) }
    var op by rememberSaveable { mutableStateOf(Op.COPY) }
    var started by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }

    // A card left over from an earlier transfer must not hide the options for this one.
    LaunchedEffect(Unit) { if (!started) TransferHub.clear() }

    fun begin(dest: Uri) {
        val taken = ArrayList<Uri>()
        if (Grants.persist(context, dest, true)) taken.add(dest)
        uris.filter { DocTree.isTree(it) || DocTree.isDocument(it) }
            .forEach { if (Grants.persist(context, it, false)) taken.add(it) }
        val effective = if (op == Op.MOVE && policy == ConflictPolicy.SKIP) ConflictPolicy.RENAME else policy
        val job = TransferJob(uris, dest, effective, op, inAppId, taken)
        if (TransferHub.start(context, TransferWork.Copy(job))) started = true
        else notice = R.string.transfer_busy
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { dest ->
        if (dest != null) begin(dest)
    }
    fun openPicker() {
        runCatching { picker.launch(null) }.onFailure { notice = R.string.transfer_no_picker }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { openPicker() }

    val current = state
    val showCard = current != null && (started || current.running)

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(
                if (role == Role.AGENT) R.string.transfer_title_space else R.string.transfer_title_personal
            ),
            style = MaterialTheme.typography.headlineSmall
        )
        when {
            showCard && current != null -> {
                TransferCard(
                    current,
                    onCancel = { TransferHub.cancel() },
                    onDismiss = {
                        TransferHub.clear()
                        onClose()
                    }
                )
            }
            uris.isEmpty() -> {
                Text(stringResource(R.string.transfer_nothing))
                Button(onClick = onClose) { Text(stringResource(R.string.transfer_close)) }
            }
            else -> {
                Text(stringResource(R.string.transfer_items, uris.size))
                if (inAppId != null) {
                    Text(stringResource(R.string.transfer_op), style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(op == Op.COPY, R.string.transfer_op_copy) { op = Op.COPY }
                    ChoiceRow(op == Op.MOVE, R.string.transfer_op_move) {
                        op = Op.MOVE
                        if (policy == ConflictPolicy.SKIP) policy = ConflictPolicy.RENAME
                    }
                    if (op == Op.MOVE) {
                        Text(
                            stringResource(R.string.transfer_move_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(stringResource(R.string.transfer_duplicates), style = MaterialTheme.typography.titleSmall)
                ChoiceRow(policy == ConflictPolicy.RENAME, R.string.transfer_rename) { policy = ConflictPolicy.RENAME }
                ChoiceRow(policy == ConflictPolicy.REPLACE, R.string.transfer_replace) { policy = ConflictPolicy.REPLACE }
                if (op == Op.COPY) {
                    ChoiceRow(policy == ConflictPolicy.SKIP, R.string.transfer_skip) { policy = ConflictPolicy.SKIP }
                }
                Button(
                    onClick = {
                        val needsPermission = Build.VERSION.SDK_INT >= 33 && role == Role.MAIN &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                        if (needsPermission) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else openPicker()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.transfer_choose)) }
                notice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                TextButton(
                    onClick = {
                        uris.forEach { Grants.release(context, it) }
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, @StringRes label: Int, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(stringResource(label))
    }
}