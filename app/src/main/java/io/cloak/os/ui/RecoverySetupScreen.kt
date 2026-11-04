package io.cloak.os.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.cloak.os.R
import io.cloak.os.bridge.Commands
import io.cloak.os.bridge.RecoveryStore
import io.cloak.os.bridge.RecoveryToken
import io.cloak.os.common.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SecondSpace/Recovery"
private const val EXPORT_NAME = "second-space-recovery-token.txt"
private const val MAX_IMPORT_BYTES = 4096

private enum class Step { INTRO, SHOW, CONFIRM, DONE }

private tailrec fun Context.hostActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> error("no activity")
}

/** Blocks screenshots and Recents thumbnails while it is in the composition. */
@Composable
internal fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.hostActivity().window
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
fun RecoverySetupScreen(modifier: Modifier = Modifier, onRestore: () -> Unit = {}, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.INTRO) }
    var token by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(RecoveryStore.isEnabledHint(context)) }
    var confirmOff by remember { mutableStateOf(false) }

    val exportedMsg = stringResource(R.string.recovery_exported)
    val exportFailedMsg = stringResource(R.string.recovery_export_failed)
    val importFailedMsg = stringResource(R.string.recovery_import_failed)
    val sendFailedMsg = stringResource(R.string.recovery_send_failed)

    if (step == Step.SHOW || step == Step.CONFIRM) SecureWindow()

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val t = token
        if (uri != null && t != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { writeToken(context, uri, t) }
                Toast.makeText(context, if (ok) exportedMsg else exportFailedMsg, Toast.LENGTH_LONG).show()
            }
        }
    }
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val found = withContext(Dispatchers.IO) { readSmall(context, uri) }
                    ?.let { RecoveryToken.extract(it) }
                if (found != null) typed = RecoveryToken.format(found)
                else Toast.makeText(context, importFailedMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun start() {
        token = RecoveryToken.generate()
        typed = ""
        step = Step.SHOW
    }

    fun cancel() {
        token = null
        typed = ""
        step = Step.INTRO
    }

    fun activate() {
        val t = token ?: return
        Commands.send(context, Commands.SET_RECOVERY, RecoveryToken.deriveKey(t))
            .onSuccess {
                RecoveryStore.setEnabledHint(context, true)
                enabled = true
                token = null
                typed = ""
                step = Step.DONE
            }
            .onFailure {
                Logx.e(TAG, "send recovery key failed", it)
                Toast.makeText(context, sendFailedMsg, Toast.LENGTH_LONG).show()
            }
    }

    fun turnOff() {
        Commands.send(context, Commands.CLEAR_RECOVERY)
            .onSuccess {
                RecoveryStore.setEnabledHint(context, false)
                enabled = false
            }
            .onFailure {
                Logx.e(TAG, "send clear recovery failed", it)
                Toast.makeText(context, sendFailedMsg, Toast.LENGTH_LONG).show()
            }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (step) {
            Step.INTRO -> IntroStep(
                enabled = enabled,
                onCreate = { start() },
                onTurnOff = { confirmOff = true },
                onRestore = onRestore
            )
            Step.SHOW -> token?.let {
                ShowStep(
                    token = it,
                    onExport = { exporter.launch(EXPORT_NAME) },
                    onContinue = { step = Step.CONFIRM },
                    onCancel = { cancel() }
                )
            }
            Step.CONFIRM -> {
                val cleaned = typed.count { c -> c.isLetterOrDigit() }
                val parsed = RecoveryToken.parse(typed)
                val matches = parsed != null && parsed == token
                val problem = when {
                    cleaned < RecoveryToken.TOTAL_LEN -> null
                    parsed == null -> R.string.recovery_invalid
                    !matches -> R.string.recovery_other
                    else -> null
                }
                ConfirmStep(
                    typed = typed,
                    onTyped = { if (it.length <= 80) typed = it },
                    problem = problem,
                    matches = matches,
                    onImport = { importer.launch(arrayOf("*/*")) },
                    onActivate = { activate() },
                    onShowAgain = { step = Step.SHOW },
                    onCancel = { cancel() }
                )
            }
            Step.DONE -> DoneStep(onDone)
        }
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text(stringResource(R.string.recovery_turn_off_title)) },
            text = { Text(stringResource(R.string.recovery_turn_off_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOff = false
                    turnOff()
                }) {
                    Text(stringResource(R.string.recovery_turn_off), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmOff = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun IntroStep(enabled: Boolean, onCreate: () -> Unit, onTurnOff: () -> Unit, onRestore: () -> Unit) {
    Text(stringResource(R.string.recovery_intro), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.recovery_intro_risk),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (enabled) {
        Text(stringResource(R.string.recovery_status_on), color = MaterialTheme.colorScheme.primary)
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.recovery_replace))
        }
        OutlinedButton(onClick = onTurnOff, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.recovery_turn_off))
        }
    } else {
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.recovery_create))
        }
    }
    TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.restore_from_settings))
    }
}

@Composable
private fun ShowStep(token: String, onExport: () -> Unit, onContinue: () -> Unit, onCancel: () -> Unit) {
    val groups = token.chunked(4)
    Text(stringResource(R.string.recovery_show_title), style = MaterialTheme.typography.titleMedium)
    Text(
        groups.take(4).joinToString("-") + "\n" + groups.drop(4).joinToString("-"),
        style = MaterialTheme.typography.titleLarge,
        fontFamily = FontFamily.Monospace
    )
    Text(
        stringResource(R.string.recovery_show_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.recovery_export))
    }
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.recovery_saved))
    }
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.cancel))
    }
}

@Composable
private fun ConfirmStep(
    typed: String,
    onTyped: (String) -> Unit,
    problem: Int?,
    matches: Boolean,
    onImport: () -> Unit,
    onActivate: () -> Unit,
    onShowAgain: () -> Unit,
    onCancel: () -> Unit
) {
    Text(stringResource(R.string.recovery_confirm_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.recovery_confirm_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value = typed,
        onValueChange = onTyped,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.recovery_field)) },
        isError = problem != null,
        supportingText = { if (problem != null) Text(stringResource(problem)) },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Password
        )
    )
    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.recovery_import))
    }
    Button(onClick = onActivate, enabled = matches, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.recovery_activate))
    }
    TextButton(onClick = onShowAgain, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.recovery_show_again))
    }
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.cancel))
    }
}

@Composable
private fun DoneStep(onDone: () -> Unit) {
    Text(stringResource(R.string.recovery_done_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.recovery_done_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.recovery_done))
    }
}

private fun writeToken(context: Context, uri: Uri, token: String): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri, "wt")?.use {
        it.write(RecoveryToken.exportText(token).toByteArray(Charsets.UTF_8))
    } ?: error("no stream")
}.onFailure { Logx.e(TAG, "export failed", it) }.isSuccess

/** Reads at most 4 KB, so a wrong file cannot cost memory or time. */
internal fun readSmall(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buf = ByteArray(MAX_IMPORT_BYTES)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        String(buf, 0, n, Charsets.UTF_8)
    }
}.onFailure { Logx.e(TAG, "import failed", it) }.getOrNull()