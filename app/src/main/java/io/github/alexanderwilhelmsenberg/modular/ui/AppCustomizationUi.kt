package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.apps.AppCustomizations
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.apps.MAX_CUSTOM_APP_LABEL_CODE_POINTS
import io.github.alexanderwilhelmsenberg.modular.apps.normalizeCustomAppLabel

@Composable
fun RenameAppDialog(
    app: LaunchableApp,
    currentLabel: String,
    originalLabel: String,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember(app.key.stableId, currentLabel) { mutableStateOf(currentLabel) }

    AlertDialog(
        modifier = Modifier.testTag("rename-app-dialog"),
        onDismissRequest = onDismiss,
        title = { Text("Rename app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Original name: $originalLabel", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = label,
                    onValueChange = { next ->
                        if (next.codePointCount(0, next.length) <= MAX_CUSTOM_APP_LABEL_CODE_POINTS) {
                            label = next
                        }
                    },
                    label = { Text("App name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rename-app-input"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val saved = normalizeCustomAppLabel(label)
                onSave(saved?.takeUnless { it == originalLabel })
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }, modifier = Modifier.testTag("rename-app-reset")) {
                    Text("Reset name")
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Restore controls are based on the raw catalog so hidden apps remain reachable. */
@Composable
fun HiddenAppsControls(
    apps: List<LaunchableApp>,
    customizations: AppCustomizations,
    onUnhide: (AppKey) -> Unit,
) {
    var managerOpen by remember { mutableStateOf(false) }
    val hiddenApps = remember(apps, customizations) {
        apps.filter { customizations.isHidden(it.key) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { customizations.labelFor(it) })
    }
    SettingsRow(
        title = "Hidden apps (${hiddenApps.size})",
        description = if (hiddenApps.isEmpty()) "No installed apps are hidden" else "Restore apps hidden from Home and search",
        icon = SettingsIcon.HIDDEN,
        modifier = Modifier.testTag("settings-hidden-apps"),
        onClick = { managerOpen = true },
    )
    if (managerOpen) AlertDialog(
        onDismissRequest = { managerOpen = false },
        title = { Text("Hidden apps") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hiddenApps.isEmpty()) {
                    Text("No installed apps are hidden.", style = MaterialTheme.typography.bodyMedium)
                }
                hiddenApps.forEach { app ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                customizations.labelFor(app),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                app.key.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(
                            onClick = { onUnhide(app.key) },
                            modifier = Modifier.testTag("unhide-app:${app.key.stableId}"),
                        ) { Text("Show") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { managerOpen = false }) { Text("Done") } },
    )
}
