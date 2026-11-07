package io.cloak.os.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import io.cloak.os.R
import kotlin.math.roundToInt

private const val HEADER_FRACTION = 0.34f
private val MIN_HEADER = 150.dp
private val MAX_HEADER = 360.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceHome(
    onLock: () -> Unit,
    onAddApps: () -> Unit,
    onTransfer: () -> Unit,
    onSettings: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val screenDp = LocalConfiguration.current.screenHeightDp
    val maxPx = with(density) { (screenDp * HEADER_FRACTION).dp.coerceIn(MIN_HEADER, MAX_HEADER).toPx() }
    val header = remember(maxPx) { HeaderCollapse(maxPx) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.space_name),
                        modifier = Modifier.graphicsLayer { alpha = header.titleAlpha }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        bottomBar = { TransferCardHost() }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).nestedScroll(header)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val h = header.heightPx.roundToInt().coerceAtLeast(0)
                        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                        layout(placeable.width, h) { placeable.placeRelative(0, 0) }
                    }
                    .clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
                Branding(header)
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onAddApps) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_apps))
                        }
                        IconButton(onClick = onLock) {
                            Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.lock))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.transfer_menu)) },
                                    onClick = { menuOpen = false; onTransfer() }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.settings)) },
                                    onClick = { menuOpen = false; onSettings() }
                                )
                            }
                        }
                    }
                    SpaceGrid(onAddApps, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Branding(header: HeaderCollapse) {
    Text(
        stringResource(R.string.space_name),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier
            .wrapContentHeight(align = Alignment.CenterVertically, unbounded = true)
            .graphicsLayer {
                alpha = header.brandAlpha
                translationY = -header.collapsedPx * 0.3f
            }
    )
}