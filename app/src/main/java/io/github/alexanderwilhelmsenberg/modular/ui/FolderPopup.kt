package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.apps.rememberAppIcon
import io.github.alexanderwilhelmsenberg.modular.organization.FolderDefinition

/** A bounded, anchored dialog; the modal scrim owns outside taps and Back. */
@Composable
fun FolderPopup(
    folder: FolderDefinition,
    catalog: List<LaunchableApp>,
    hiddenKeys: Set<AppKey>,
    pausedProfileSerials: Set<Long>,
    onDismiss: () -> Unit,
    onLaunch: (LaunchableApp) -> Unit,
    onOpenActions: (LaunchableApp) -> Unit,
    onAddApps: () -> Unit,
    onEditFolder: () -> Unit,
    anchorBounds: IntRect? = null,
    iconForApp: @Composable (LaunchableApp) -> ImageBitmap? = { rememberAppIcon(it) },
) {
    val byKey = remember(catalog) { catalog.associateBy { it.key } }
    val visibleMembers = remember(folder.members, hiddenKeys) { folder.members.filter { it !in hiddenKeys } }
    val hiddenCount = folder.members.size - visibleMembers.size
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().testTag("folder-popup-overlay")) {
            Box(Modifier.fillMaxSize().clickable(onClick = onDismiss))
            val popupWidth = maxWidth.coerceAtMost(360.dp) - 32.dp
            val popupHeight = maxHeight.coerceAtMost(480.dp) - 48.dp
            val x = if (anchorBounds == null) (maxWidth - popupWidth) / 2 else
                with(density) { anchorBounds.center.x.toDp() - popupWidth / 2 }
                    .coerceIn(16.dp, (maxWidth - popupWidth - 16.dp).coerceAtLeast(16.dp))
            val y = if (anchorBounds == null) (maxHeight - popupHeight) / 2 else {
                val above = with(density) { anchorBounds.top.toDp() } - popupHeight - 8.dp
                val below = with(density) { anchorBounds.bottom.toDp() } + 8.dp
                (if (above >= 16.dp) above else below)
                    .coerceIn(16.dp, (maxHeight - popupHeight - 16.dp).coerceAtLeast(16.dp))
            }
            Card(
                modifier = Modifier
                    .width(popupWidth)
                    .heightIn(max = popupHeight)
                    .offsetPx(with(density) { x.roundToPx() }, with(density) { y.roundToPx() })
                    .testTag("folder-popup:${folder.id}"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(folder.name, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { onDismiss(); onEditFolder() }) { Text("Edit") }
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                    if (visibleMembers.isEmpty()) {
                        Column(Modifier.padding(20.dp)) {
                            Text(if (folder.members.isEmpty()) "This folder is empty." else "No visible apps in this folder.")
                            if (hiddenCount > 0) Text("$hiddenCount hidden app${if (hiddenCount == 1) "" else "s"} can be managed in folder settings.",
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onDismiss(); onAddApps() }) { Text("Add apps") }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = popupHeight - 72.dp)) {
                            items(visibleMembers, key = { it.stableId }) { key ->
                                val app = byKey[key]
                                val reason = when {
                                    app == null -> "Unavailable app"
                                    key.profileSerial in pausedProfileSerials -> "Paused profile"
                                    !app.enabled -> "Unavailable app"
                                    else -> null
                                }
                                if (app == null) {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                                        Text(key.packageName, style = MaterialTheme.typography.bodyLarge)
                                        Text("Unavailable · ${key.activityName} · profile ${key.profileSerial}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                } else {
                                    FolderPopupAppRow(app, iconForApp(app), reason,
                                        onClick = { onDismiss(); onLaunch(app) },
                                        onLongClick = { onDismiss(); onOpenActions(app) })
                                }
                            }
                            if (hiddenCount > 0) item {
                                Text("$hiddenCount hidden app${if (hiddenCount == 1) "" else "s"} can be managed in folder settings.",
                                    modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FolderPopupAppRow(app: LaunchableApp, icon: ImageBitmap?, unavailableReason: String?,
    onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp)
            .combinedClickable(onClick = { if (unavailableReason == null) onClick() },
                onLongClick = onLongClick, onLongClickLabel = "App actions")
            .testTag("folder-app:${app.key.stableId}")
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon == null) Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer,
            RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Text(app.label.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer)
        } else Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (unavailableReason != null) Text(unavailableReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Modifier.offsetPx(x: Int, y: Int): Modifier =
    this.then(Modifier.offset { IntOffset(x, y) })
