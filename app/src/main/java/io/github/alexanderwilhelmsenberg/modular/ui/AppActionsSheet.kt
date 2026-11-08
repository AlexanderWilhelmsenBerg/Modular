package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.R
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppActionsSheet(
    app: LaunchableApp,
    icon: ImageBitmap?,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onPinApp: ((LaunchableApp, Boolean) -> Unit)?,
    onAppInfo: ((LaunchableApp) -> Unit)?,
    onUninstallApp: ((LaunchableApp) -> Unit)?,
    onRenameApp: ((LaunchableApp) -> Unit)?,
    onHideApp: ((LaunchableApp) -> Unit)?,
    onOrganizeApp: ((LaunchableApp) -> Unit)? = null,
    onRemoveFromFolder: ((LaunchableApp) -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("app-actions-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (icon != null) {
                        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(48.dp))
                    } else {
                        Box(
                            Modifier.size(44.dp).background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(11.dp),
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                app.label.firstOrNull()?.uppercase() ?: "?",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        app.key.packageName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider()

            if (onPinApp != null) AppActionRow(
                label = if (pinned) "Unpin from starred apps" else "Pin to starred apps",
                tag = "app-action:pin",
                iconRes = R.drawable.ic_app_action_pin,
                onClick = { onDismiss(); onPinApp(app, !pinned) },
            )
            if (onOrganizeApp != null) AppActionRow(
                label = "Add to category",
                tag = "app-action:organize",
                iconRes = R.drawable.ic_app_action_edit,
                onClick = { onDismiss(); onOrganizeApp(app) },
            )
            if (onRemoveFromFolder != null) AppActionRow(
                label = "Remove from this folder",
                tag = "app-action:remove-from-folder",
                iconRes = R.drawable.ic_app_action_delete,
                onClick = { onDismiss(); onRemoveFromFolder(app) },
            )
            if (onAppInfo != null) AppActionRow(
                label = "App info",
                tag = "app-action:info",
                iconRes = R.drawable.ic_app_action_info,
                onClick = { onDismiss(); onAppInfo(app) },
            )
            if (onRenameApp != null) AppActionRow(
                label = "Rename",
                tag = "app-action:rename",
                iconRes = R.drawable.ic_app_action_edit,
                onClick = { onDismiss(); onRenameApp(app) },
            )
            if (onHideApp != null) AppActionRow(
                label = "Hide",
                tag = "app-action:hide",
                iconRes = R.drawable.ic_app_action_hide,
                onClick = { onDismiss(); onHideApp(app) },
            )
            if (onUninstallApp != null) AppActionRow(
                label = "Uninstall",
                tag = "app-action:uninstall",
                iconRes = R.drawable.ic_app_action_delete,
                onClick = { onDismiss(); onUninstallApp(app) },
            )
        }
    }
}

@Composable
private fun AppActionRow(label: String, tag: String, iconRes: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(20.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
