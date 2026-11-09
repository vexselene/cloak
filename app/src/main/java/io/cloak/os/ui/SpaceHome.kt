package io.cloak.os.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import io.cloak.os.R
import kotlin.math.roundToInt

private const val HEADER_FRACTION = 0.22f
private val MIN_HEADER = 120.dp
private val MAX_HEADER = 300.dp

/** Size of each round button in the row above the card. 48dp was the Material default. */
private val BAR_BUTTON = 38.dp

/** Extra space between the buttons. 0.dp puts them edge to edge. */
private val BAR_GAP = 2.dp

/** Space above the title row. Secure Folder uses about 20dp. */
private val BAR_TOP = 20.dp

/** Space between the title row and the card. Secure Folder uses about 14dp. */
private val BAR_BOTTOM = 14.dp

/** Left edge lines up with the card's inner content. */
private val BAR_START = 28.dp
private val BAR_END = 18.dp

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
            Row(
                Modifier.fillMaxWidth().padding(
                    start = BAR_START,
                    end = BAR_END,
                    top = BAR_TOP,
                    bottom = BAR_BOTTOM
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.space_name),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).graphicsLayer { alpha = header.titleAlpha }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(BAR_GAP)) {
                    BarIcon(Icons.Default.Add, stringResource(R.string.add_apps), onAddApps)
                    BarIcon(Icons.Default.Lock, stringResource(R.string.lock), onLock)
                    Box {
                        BarIcon(Icons.Default.MoreVert, stringResource(R.string.more)) { menuOpen = true }
                        SpaceDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ) {
                SpaceGrid(onAddApps, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(BAR_BUTTON)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Branding(header: HeaderCollapse) {
    Text(
        stringResource(R.string.space_name),
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier
            .wrapContentHeight(align = Alignment.CenterVertically, unbounded = true)
            .graphicsLayer {
                alpha = header.brandAlpha
                translationY = -header.collapsedPx * 0.3f
            }
    )
}