package io.cloak.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.cloak.os.R
import io.cloak.os.common.Role
import io.cloak.os.debug.DebugPanel
import androidx.compose.material3.CircularProgressIndicator
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.remember
import io.cloak.os.bridge.SpaceProfile
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import io.cloak.os.ui.theme.SquircleShape
@Composable
fun WelcomeScreen(allowed: Boolean, onCreate: () -> Unit) {
    CenteredPage(
        title = stringResource(R.string.welcome_title),
        body = stringResource(R.string.welcome_body)
    ) {
        Button(onClick = onCreate, enabled = allowed) { Text(stringResource(R.string.create_space)) }
        if (!allowed) {
            Text(
                stringResource(R.string.provisioning_not_allowed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun LockedScreen(paused: Boolean, onUnlock: () -> Unit) {
    CenteredPage(
        title = stringResource(if (paused) R.string.space_paused else R.string.space_locked),
        body = stringResource(R.string.locked_body)
    ) {
        Button(onClick = onUnlock) { Text(stringResource(R.string.unlock)) }
    }
}

@Composable
fun UnpairedScreen(onRecover: () -> Unit) {
    val context = LocalContext.current
    val failedMsg = stringResource(R.string.unpaired_settings_failed)
    CenteredPage(
        title = stringResource(R.string.unpaired_title),
        body = stringResource(R.string.unpaired_body)
    ) {
        Button(onClick = onRecover) { Text(stringResource(R.string.unpaired_recover)) }
        OutlinedButton(onClick = {
            if (!openProfileSettings(context)) {
                Toast.makeText(context, failedMsg, Toast.LENGTH_LONG).show()
            }
        }) { Text(stringResource(R.string.unpaired_open_settings)) }
        Text(
            stringResource(R.string.unpaired_where),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ForeignScreen(onCheckAgain: () -> Unit) {
    val context = LocalContext.current
    val failedMsg = stringResource(R.string.unpaired_settings_failed)
    val cloakInside = remember { SpaceProfile.detect(context).cloakInside }
    CenteredPage(
        title = stringResource(R.string.foreign_title),
        body = stringResource(R.string.foreign_body)
    ) {
        Button(onClick = onCheckAgain) { Text(stringResource(R.string.foreign_check)) }
        OutlinedButton(onClick = {
            if (!openProfileSettings(context)) {
                Toast.makeText(context, failedMsg, Toast.LENGTH_LONG).show()
            }
        }) { Text(stringResource(R.string.foreign_open_settings)) }
        if (cloakInside) {
            Text(
                stringResource(R.string.foreign_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun CenteredPage(title: String, body: String, actions: @Composable ColumnScope.() -> Unit) {
    Scaffold(containerColor = Color.Transparent) { inner ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(inner)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
            ) {
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(SquircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.ic_logo_mark),
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                actions()
                DebugPanel(Role.MAIN)
            }
        }
    }
}

@Composable
fun OpeningScreen() {
    CenteredPage(title = stringResource(R.string.opening_title), body = "") {
        CircularProgressIndicator()
    }
}