package io.cloak.os.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.cloak.os.R
import io.cloak.os.apps.SpaceApp
import io.cloak.os.apps.SpaceApps
import io.cloak.os.bridge.Commands
import io.cloak.os.common.Logx
import io.cloak.os.common.Role
import io.cloak.os.debug.DebugPanel
import androidx.compose.runtime.collectAsState

private val ICON_SIZE = 56.dp

@Composable
fun SpaceGrid(onAddApps: () -> Unit, modifier: Modifier = Modifier) {
    val apps by rememberSpaceApps()
    val settling by AppListRefresh.settling.collectAsState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(72.dp),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (apps.isEmpty()) {
            item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(if (settling) R.string.grid_loading else R.string.empty_space_hint),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        items(apps, key = { it.packageName }) { app -> AppTile(app) }
        item(key = "debug", span = { GridItemSpan(maxLineSpan) }) { DebugPanel(Role.MAIN) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(app: SpaceApp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { ICON_SIZE.roundToPx() }
    var menuOpen by remember { mutableStateOf(false) }
    val bitmap = remember(app.icon, px) { IconRenderer.render(app.icon, px).asImageBitmap() }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = {
                        SpaceApps.launch(context, app)
                            .onFailure { Logx.e("SecondSpace/Grid", "launch failed", it) }
                    },
                    onLongClick = { menuOpen = true }
                )
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = app.label,
                modifier = Modifier.size(ICON_SIZE),
                alpha = if (app.suspended) 0.4f else 1f
            )
            Spacer(Modifier.height(8.dp))
            Text(
                app.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center
            )
        }
        SpaceDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (app.suspended) R.string.unfreeze else R.string.freeze)) },
                onClick = {
                    menuOpen = false
                    Commands.send(
                        context,
                        if (app.suspended) Commands.UNFREEZE else Commands.FREEZE,
                        app.packageName
                    )
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remove_from_space)) },
                onClick = {
                    menuOpen = false
                    Commands.send(context, Commands.UNINSTALL, app.packageName)
                }
            )
        }
    }
}