package io.cloak.os.ui

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.cloak.os.R
import io.cloak.os.bridge.Auth
import io.cloak.os.bridge.Liveness
import io.cloak.os.bridge.RecoveryClient
import io.cloak.os.bridge.RecoveryProof
import io.cloak.os.bridge.RecoveryStore
import io.cloak.os.bridge.RecoveryToken
import io.cloak.os.common.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SecondSpace/Restore"

private enum class Phase { ENTER, CONFIRM }

@Composable
fun RecoveryRestoreScreen(modifier: Modifier = Modifier, onDone: () -> Unit) {
    SecureWindow()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val liveness by rememberProfileLiveness()
    var phase by remember {
        mutableStateOf(if (RecoveryStore.mainPending(context) != null) Phase.CONFIRM else Phase.ENTER)
    }
    var typed by remember { mutableStateOf("") }

    val importFailedMsg = stringResource(R.string.recovery_import_failed)
    val unreachableMsg = stringResource(R.string.restore_unreachable)
    val expiredMsg = stringResource(R.string.restore_expired)

    fun promote() {
        val pending = RecoveryStore.mainPending(context)
        if (pending != null && Auth.replaceSecret(context, pending)) {
            RecoveryStore.clearMainPending(context)
            typed = ""
            onDone()
        } else {
            RecoveryStore.clearMainPending(context)
            phase = Phase.ENTER
            Toast.makeText(context, expiredMsg, Toast.LENGTH_LONG).show()
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Logx.d(TAG, "recover result=${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) promote() else phase = Phase.CONFIRM
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val found = withContext(Dispatchers.IO) { readSmall(context, uri) }
                    ?.let { RecoveryToken.extract(it) }
                if (found != null) typed = RecoveryToken.format(found)
                else Toast.makeText(context, importFailedMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun start(token: String) {
        val secret = Auth.generateSecret()
        val ts = System.currentTimeMillis()
        val proof = RecoveryProof.compute(RecoveryToken.deriveKey(token), secret, ts)
        RecoveryStore.setMainPending(context, secret)
        runCatching { launcher.launch(RecoveryClient.intent(secret, ts, proof)) }
            .onSuccess {
                typed = ""
                phase = Phase.CONFIRM
            }
            .onFailure {
                Logx.e(TAG, "recover launch failed", it)
                RecoveryStore.clearMainPending(context)
                Toast.makeText(context, unreachableMsg, Toast.LENGTH_LONG).show()
            }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (phase) {
            Phase.ENTER -> {
                val cleaned = typed.count { it.isLetterOrDigit() }
                val parsed = RecoveryToken.parse(typed)
                val problem = if (cleaned >= RecoveryToken.TOTAL_LEN && parsed == null) {
                    R.string.recovery_invalid
                } else null

                Text(stringResource(R.string.restore_body), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(
                        when (liveness) {
                            Liveness.UNLOCKED -> R.string.restore_status_ok
                            Liveness.PAUSED -> R.string.restore_status_paused
                            Liveness.LOCKED -> R.string.restore_status_locked
                            Liveness.MISSING -> R.string.restore_status_missing
                        }
                    ),
                    color = if (liveness == Liveness.UNLOCKED) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (liveness == Liveness.PAUSED || liveness == Liveness.LOCKED) {
                    OutlinedButton(
                        onClick = { RecoveryClient.wake(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.restore_unlock)) }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { if (it.length <= 80) typed = it },
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
                OutlinedButton(
                    onClick = { importer.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.recovery_import)) }
                Button(
                    onClick = { parsed?.let { start(it) } },
                    enabled = parsed != null && liveness == Liveness.UNLOCKED,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.restore_go)) }
                Text(
                    stringResource(R.string.restore_pin_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Phase.CONFIRM -> {
                Text(stringResource(R.string.restore_waiting_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.restore_waiting_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = { promote() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.restore_worked))
                }
                TextButton(
                    onClick = {
                        RecoveryStore.clearMainPending(context)
                        phase = Phase.ENTER
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.restore_retry)) }
            }
        }
    }
}