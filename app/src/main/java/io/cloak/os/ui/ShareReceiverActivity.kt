package io.cloak.os.ui

import android.Manifest
import android.content.Context
import android.content.Intent
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
import io.cloak.os.common.Role
import io.cloak.os.transfer.ConflictPolicy
import io.cloak.os.transfer.DocTree
import io.cloak.os.transfer.ShareIntents
import io.cloak.os.transfer.TransferHub
import io.cloak.os.transfer.TransferJob
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
        val role = Role.detect(this)
        setContent {
            SpaceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().background(SpaceBackground),
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) { ReceiverScreen(uris, role) { finish() } }
            }
        }
    }

    private companion object {
        const val MAX_ITEMS = 500
    }
}

private fun persist(context: Context, uri: Uri, write: Boolean): Boolean = runCatching {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
    context.contentResolver.takePersistableUriPermission(uri, flags)
    true
}.getOrDefault(false)

@Composable
private fun ReceiverScreen(uris: List<Uri>, role: Role, onClose: () -> Unit) {
    val context = LocalContext.current
    val state by TransferHub.state.collectAsState()
    var policy by rememberSaveable { mutableStateOf(ConflictPolicy.RENAME) }
    var notice by remember { mutableStateOf<Int?>(null) }

    fun begin(dest: Uri) {
        val taken = ArrayList<Uri>()
        if (persist(context, dest, true)) taken.add(dest)
        uris.filter { DocTree.isTree(it) || DocTree.isDocument(it) }
            .forEach { if (persist(context, it, false)) taken.add(it) }
        if (!TransferHub.start(context, TransferJob(uris, dest, policy, taken))) {
            notice = R.string.transfer_busy
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { dest ->
        if (dest != null) begin(dest)
    }
    fun openPicker() {
        runCatching { picker.launch(null) }.onFailure { notice = R.string.transfer_no_picker }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { openPicker() }

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
        val s = state
        when {
            s != null -> {
                TransferCard(
                    s,
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
                Text(stringResource(R.string.transfer_duplicates), style = MaterialTheme.typography.titleSmall)
                PolicyOption(policy == ConflictPolicy.RENAME, R.string.transfer_rename) { policy = ConflictPolicy.RENAME }
                PolicyOption(policy == ConflictPolicy.REPLACE, R.string.transfer_replace) { policy = ConflictPolicy.REPLACE }
                PolicyOption(policy == ConflictPolicy.SKIP, R.string.transfer_skip) { policy = ConflictPolicy.SKIP }
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
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun PolicyOption(selected: Boolean, @StringRes label: Int, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(stringResource(label))
    }
}