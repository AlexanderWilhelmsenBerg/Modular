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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppCustomizations
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.organization.HeadsetKind
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import io.github.alexanderwilhelmsenberg.modular.workspace.ListeningAppsProjector
import io.github.alexanderwilhelmsenberg.modular.workspace.ListeningConnectionStatus
import io.github.alexanderwilhelmsenberg.modular.workspace.ListeningPreferences
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.launch

/** Foreground Listening configuration. Selected keys are retained if an app disappears. */
@Composable
fun ListeningAppsSettings(
    snapshot: OrganizationSnapshot,
    catalog: AppCatalog,
    customizations: AppCustomizations,
    headsetOutputs: Set<HeadsetKind>?,
    onUpdate: suspend (OrganizationBatch) -> Result<OrganizationSnapshot>,
    showEntry: Boolean = true,
    openDetailsRequest: Int = 0,
) {
    val scope = rememberCoroutineScope()
    var detailsOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(snapshot.listeningPreferences) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openDetailsRequest) {
        if (openDetailsRequest > 0) detailsOpen = true
    }
    fun open() {
        draft = snapshot.listeningPreferences
        error = null
        detailsOpen = true
    }
    fun save(preferences: ListeningPreferences, closeOnSuccess: Boolean) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val result = runCatching {
                onUpdate(OrganizationBatch(listOf(OrganizationEdit.SetListeningPreferences(preferences)),
                    expectedRevision = snapshot.revision))
            }.getOrElse { Result.failure(it) }
            busy = false
            result.onSuccess { if (closeOnSuccess) detailsOpen = false }
                .onFailure { error = it.message ?: "Could not save Listening apps. Try again." }
        }
    }

    if (showEntry) Column(Modifier.fillMaxWidth().testTag("listening-apps-settings")) {
        SettingsRow(
            title = "Listening apps",
            description = "Show chosen apps when a headset connects",
            icon = SettingsIcon.APPS,
            onClick = ::open,
            trailing = {
                Switch(
                    checked = snapshot.listeningPreferences.enabled,
                    enabled = !busy,
                    onCheckedChange = { enabled ->
                        save(snapshot.listeningPreferences.copy(enabled = enabled), closeOnSuccess = false)
                    },
                    modifier = Modifier.semantics { contentDescription = "Listening apps" }
                        .testTag("listening-apps-toggle"),
                )
            },
        )
        TextButton(onClick = ::open, modifier = Modifier.padding(start = 76.dp)
            .testTag("listening-apps-configure")) { Text("Configure Listening apps") }
        if (!detailsOpen) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (detailsOpen) {
        val projection = ListeningAppsProjector.project(catalog, customizations, draft, headsetOutputs)
        val connection = ListeningAppsProjector.project(catalog, customizations,
            draft.copy(enabled = true), headsetOutputs).status
        val byKey = catalog.apps.associateBy { it.key }
        AlertDialog(
            onDismissRequest = { if (!busy && !pickerOpen) detailsOpen = false },
            title = { Text("Listening apps") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Show on Home", Modifier.padding(top = 12.dp))
                        Switch(draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) },
                            modifier = Modifier.testTag("listening-dialog-enabled"))
                    }
                    Text("Trigger output", style = MaterialTheme.typography.titleSmall)
                    HeadsetKind.entries.forEach { kind ->
                        FilterChip(draft.kind == kind,
                            onClick = { draft = draft.copy(kind = kind) },
                            label = { Text(kind.displayName()) },
                            modifier = Modifier.testTag("listening-kind:${kind.name}"))
                    }
                    Text("Current connection: ${connection.displayName()}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("listening-connection-status"))
                    Text("Chosen apps", style = MaterialTheme.typography.titleSmall)
                    if (draft.apps.isEmpty()) Text("Choose at least one app before a section can appear.",
                        style = MaterialTheme.typography.bodySmall)
                    draft.apps.distinct().forEachIndexed { index, key ->
                        val app = byKey[key]
                        val available = key !in projection.unavailableKeys
                        Column(Modifier.fillMaxWidth().testTag("listening-selected:${key.stableId}")) {
                            Text(app?.let(customizations::labelFor) ?: key.packageName)
                            Text("${key.packageName}/${key.activityName} · profile ${key.profileSerial}" +
                                if (available) "" else " · unavailable or hidden",
                                style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(enabled = index > 0, onClick = {
                                    draft = draft.copy(apps = draft.apps.moved(index, index - 1))
                                }) { Text("Up") }
                                TextButton(enabled = index < draft.apps.size - 1, onClick = {
                                    draft = draft.copy(apps = draft.apps.moved(index, index + 1))
                                }) { Text("Down") }
                                TextButton(onClick = {
                                    draft = draft.copy(apps = draft.apps.filterNot { it == key })
                                }) { Text("Remove") }
                            }
                        }
                    }
                    OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()
                        .testTag("listening-choose-apps")) { Text("Choose apps") }
                    Text("Preview", style = MaterialTheme.typography.titleSmall)
                    if (projection.availableApps.isEmpty()) Text("No chosen apps are currently available.",
                        style = MaterialTheme.typography.bodySmall)
                    else projection.availableApps.forEach { app ->
                        Text(customizations.labelFor(app), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (connection == ListeningConnectionStatus.UNKNOWN) Text(
                        "Connection could not be checked. The section stays hidden until a headset is confirmed.",
                        style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { Button(enabled = !busy, onClick = { save(draft.copy(apps = draft.apps.distinct()), true) }) {
                Text("Save")
            } },
            dismissButton = { TextButton(enabled = !busy, onClick = { detailsOpen = false }) { Text("Cancel") } },
        )
        if (pickerOpen) ListeningAppPicker(catalog.apps, customizations, draft.apps,
            onChange = { draft = draft.copy(apps = it) }, onDismiss = { pickerOpen = false })
    }
}

@Composable
private fun ListeningAppPicker(
    catalog: List<LaunchableApp>,
    customizations: AppCustomizations,
    selected: List<AppKey>,
    onChange: (List<AppKey>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val apps = remember(catalog, customizations, query) {
        val collator = Collator.getInstance(Locale.getDefault())
        catalog.filter { app -> query.isBlank() ||
            customizations.labelFor(app).contains(query, ignoreCase = true) ||
            app.key.packageName.contains(query, ignoreCase = true)
        }.sortedWith { a, b ->
            collator.compare(customizations.labelFor(a), customizations.labelFor(b)).takeIf { it != 0 }
                ?: a.key.stableId.compareTo(b.key.stableId)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Listening apps") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 490.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(query, onValueChange = { query = it }, label = { Text("Search label or package") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("listening-app-search"))
                apps.forEach { app ->
                    Row(Modifier.fillMaxWidth().testTag("listening-picker:${app.key.stableId}"),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f).padding(top = 5.dp)) {
                            Text(customizations.labelFor(app))
                            Text("${app.key.packageName}/${app.key.activityName} · profile ${app.key.profileSerial}" +
                                if (customizations.isHidden(app.key)) " · hidden" else "",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Checkbox(app.key in selected, onCheckedChange = { checked ->
                            onChange(if (checked) (selected + app.key).distinct()
                                else selected.filterNot { it == app.key })
                        })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun List<AppKey>.moved(from: Int, to: Int): List<AppKey> = toMutableList().apply {
    add(to, removeAt(from))
}

private fun HeadsetKind.displayName(): String = when (this) {
    HeadsetKind.ANY -> "Any headset"
    HeadsetKind.WIRED -> "Wired"
    HeadsetKind.USB -> "USB"
    HeadsetKind.BLUETOOTH -> "Bluetooth"
}

private fun ListeningConnectionStatus.displayName(): String = when (this) {
    ListeningConnectionStatus.DISABLED -> "Off"
    ListeningConnectionStatus.UNKNOWN -> "Unknown"
    ListeningConnectionStatus.DISCONNECTED -> "No matching headset connected"
    ListeningConnectionStatus.CONNECTED -> "Matching headset connected"
}
