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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
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
private fun CenteredPage(title: String, body: String, actions: @Composable ColumnScope.() -> Unit) {
    Scaffold(containerColor = Color.Transparent) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            Box(
                Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
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

@Composable
fun OpeningScreen() {
    CenteredPage(title = stringResource(R.string.opening_title), body = "") {
        CircularProgressIndicator()
    }
}