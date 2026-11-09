package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.mostused.MostUsedSource
import java.util.Locale
import kotlinx.coroutines.launch

/** Settings for Core's built-in Most Used feature. Callers provide the persisted state. */
@Composable
fun CoreMostUsedSettingsUi(
    enabled: Boolean,
    days: Int,
    suggestedCount: Int,
    pinnedKeys: List<AppKey>,
    unavailablePinnedKeys: Collection<AppKey>,
    hasUsageAccess: Boolean,
    catalog: List<LaunchableApp>,
    onSetEnabled: suspend (Boolean) -> Result<Unit>,
    onSetDays: suspend (Int) -> Result<Unit>,
    onSetSuggestedCount: suspend (Int) -> Result<Unit>,
    onSetPinned: suspend (AppKey, Boolean) -> Result<Unit>,
    onReorderPins: suspend (List<AppKey>) -> Result<Unit>,
    onOpenUsageAccess: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onRefresh: () -> Unit,
    loading: Boolean = false,
    loadError: String? = null,
    statusMessage: String? = null,
    configureRequest: Int = 0,
    hasImportedSettings: Boolean = false,
    source: MostUsedSource = MostUsedSource.LOCAL,
    usingLocalFallback: Boolean = false,
    onSetSource: suspend (MostUsedSource) -> Result<Unit> = { Result.success(Unit) },
    onResetLearnedUsage: suspend () -> Result<Unit> = { Result.success(Unit) },
    onConfigureRequestConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var restrictedTipOpen by remember { mutableStateOf(false) }
    var resetConfirmOpen by remember { mutableStateOf(false) }
    var resetNotice by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val byKey = remember(catalog) { catalog.associateBy { it.key } }
    val duplicateKeys = remember(catalog) {
        catalog.groupBy { it.label.lowercase(Locale.ROOT) }.values
            .filter { it.size > 1 }.flatMap { group -> group.map { it.key } }.toSet()
    }
    LaunchedEffect(configureRequest) {
        if (configureRequest > 0) {
            detailsOpen = true
            onConfigureRequestConsumed()
        }
    }

    fun save(afterSuccess: () -> Unit = {}, action: suspend () -> Result<Unit>) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val result = runCatching { action() }.getOrElse { Result.failure(it) }
            busy = false
            result.onSuccess { afterSuccess() }.onFailure {
                error = it.message ?: "Could not save Most Used settings. Try again."
            }
        }
    }

    Column(Modifier.fillMaxWidth().testTag("core-most-used-card")) {
        SettingsRow(
            title = "Most Used",
            description = "Show pinned and frequent apps at the top of App drawer",
            icon = SettingsIcon.STAR,
            onClick = { detailsOpen = true },
            trailing = {
                Switch(
                    checked = enabled,
                    enabled = !busy,
                    onCheckedChange = { checked -> save { onSetEnabled(checked) } },
                    modifier = Modifier.semantics { contentDescription = "Most Used" }
                        .testTag("core-most-used-toggle"),
                )
            },
        )
        Column(Modifier.fillMaxWidth().padding(start = 76.dp, end = 24.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when (source) {
                    MostUsedSource.LOCAL -> "Learns from apps launched through Modular · no permission needed"
                    MostUsedSource.ANDROID -> when {
                        usingLocalFallback || !hasUsageAccess -> "Android history unavailable · using Modular launches for suggestions"
                        else -> "Android usage history is available"
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { detailsOpen = true }, modifier = Modifier.testTag("core-most-used-configure")) {
                Text("Configure Most Used")
            }
            if (!enabled && hasImportedSettings) Text(
                "Previous Most Used settings are saved. Turn on Most Used to show your pins in App drawer.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!detailsOpen) {
                loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    if (restrictedTipOpen) AlertDialog(
        onDismissRequest = { restrictedTipOpen = false },
        title = { Text("If Usage Access is blocked") },
        text = { Text("On Samsung, first tap Modular's disabled row in Usage Access and dismiss the “Restricted setting” dialog. Then reopen Modular's App info, open the top-right menu, and tap “Allow restricted settings”. Return to Usage Access and enable Modular. This menu option appears only after Android has restricted the app. Android controls this restriction; Modular cannot bypass it.") },
        confirmButton = { Button(onClick = { restrictedTipOpen = false; onOpenAppInfo() }) { Text("Open App info") } },
        dismissButton = { TextButton(onClick = { restrictedTipOpen = false }) { Text("Close") } },
    )

    if (detailsOpen) AlertDialog(
        onDismissRequest = { if (!busy) detailsOpen = false },
        title = { Text("Most Used") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Pinned apps always come first. Suggestions use activity from the past $days days.", style = MaterialTheme.typography.bodyMedium)
                Text("Suggestion source", style = MaterialTheme.typography.titleMedium)
                SourceChoice(
                    label = "Learn from Modular",
                    description = "Counts apps you launch through Modular while Most Used is enabled. No access needed.",
                    selected = source == MostUsedSource.LOCAL,
                    enabled = !busy,
                    tag = "core-most-used-source-local",
                ) { save { onSetSource(MostUsedSource.LOCAL) } }
                SourceChoice(
                    label = "Android usage history",
                    description = "Uses foreground time across the whole device. Requires Usage Access.",
                    selected = source == MostUsedSource.ANDROID,
                    enabled = !busy,
                    tag = "core-most-used-source-android",
                ) { save { onSetSource(MostUsedSource.ANDROID) } }
                if (source == MostUsedSource.ANDROID) {
                    Text(
                        when {
                            usingLocalFallback || !hasUsageAccess -> "Android history is unavailable. Suggestions use Modular launches until Android history becomes available."
                            else -> "Usage Access is on for Modular."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onOpenUsageAccess, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Usage Access")
                    }
                    TextButton(onClick = { detailsOpen = false; restrictedTipOpen = true }) {
                        Text("Usage Access blocked?")
                    }
                }
                if (loading) Text("Loading saved settings…")
                loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (resetNotice) Text("Modular's learned usage was reset.", style = MaterialTheme.typography.bodySmall)
                NumberSetting("History", "days", days, 1, 30, busy) {
                    save { onSetDays(it) }
                }
                NumberSetting("Suggestions", "apps", suggestedCount, 0, 20, busy) {
                    save { onSetSuggestedCount(it) }
                }
                Text("Pinned apps", style = MaterialTheme.typography.titleMedium)
                if (pinnedKeys.isEmpty()) Text("No apps pinned yet. Pins appear before suggestions.")
                pinnedKeys.forEachIndexed { index, key ->
                    val app = byKey[key]
                    val unavailable = key in unavailablePinnedKeys || app == null
                    Column {
                        Text(
                            if (unavailable) "${app?.label ?: key.packageName} (unavailable)" else app?.label ?: key.packageName,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (unavailable || key in duplicateKeys) Text(
                            coreIdentityHint(key), style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(enabled = !busy && index > 0, onClick = {
                                save { onReorderPins(pinnedKeys.toMutableList().apply { add(index - 1, removeAt(index)) }) }
                            }) { Text("Up") }
                            TextButton(enabled = !busy && index < pinnedKeys.lastIndex, onClick = {
                                save { onReorderPins(pinnedKeys.toMutableList().apply { add(index + 1, removeAt(index)) }) }
                            }) { Text("Down") }
                            TextButton(enabled = !busy, onClick = { save { onSetPinned(key, false) } }) {
                                Text("Remove")
                            }
                        }
                    }
                }
                OutlinedButton(
                    enabled = !busy && pinnedKeys.size < 50,
                    onClick = { pickerOpen = !pickerOpen },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (pickerOpen) "Close app picker" else "Pin apps") }
                if (pickerOpen) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Find an app") },
                        modifier = Modifier.fillMaxWidth().testTag("core-most-used-picker-search"),
                        singleLine = true,
                    )
                    val choices = remember(catalog, pinnedKeys, query) {
                        catalog.asSequence()
                            .filter { it.enabled && it.key !in pinnedKeys &&
                                (query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                                    it.key.packageName.contains(query, ignoreCase = true)) }
                            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                            .toList()
                    }
                    LazyColumn(Modifier.fillMaxWidth().height(220.dp)) {
                        items(choices, key = { it.key.stableId }) { app ->
                            TextButton(
                                enabled = !busy,
                                onClick = { save { onSetPinned(app.key, true) } },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (app.key in duplicateKeys) "${app.label} · ${coreIdentityHint(app.key)}" else app.label)
                            }
                        }
                        if (choices.isEmpty()) item { Text("No matching apps.") }
                    }
                }
                OutlinedButton(enabled = !busy, onClick = { resetNotice = false; error = null; resetConfirmOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reset learned usage…")
                }
                TextButton(onClick = { error = null; onRefresh() }) { Text("Refresh") }
                if (!resetConfirmOpen) error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { detailsOpen = false }) { Text("Done") } },
    )

    if (resetConfirmOpen) AlertDialog(
        onDismissRequest = { if (!busy) resetConfirmOpen = false },
        title = { Text("Reset learned usage?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This clears only launch history learned by Modular. Your pins, Most Used settings, and Android usage history stay as they are.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                save(afterSuccess = { resetConfirmOpen = false; resetNotice = true }) { onResetLearnedUsage() }
            }) { Text("Reset learned usage") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { resetConfirmOpen = false }) { Text("Cancel") } },
    )
}

@Composable
private fun SourceChoice(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        ).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NumberSetting(
    title: String,
    unit: String,
    value: Int,
    min: Int,
    max: Int,
    busy: Boolean,
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$title: $value $unit", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(enabled = !busy && value > min, onClick = { onChange(value - 1) },
            modifier = Modifier.testTag("core-most-used-${title.lowercase(Locale.ROOT)}-decrease")) { Text("−") }
        TextButton(enabled = !busy && value < max, onClick = { onChange(value + 1) },
            modifier = Modifier.testTag("core-most-used-${title.lowercase(Locale.ROOT)}-increase")) { Text("+") }
    }
}

private fun coreIdentityHint(key: AppKey): String =
    "${key.packageName}/${key.activityName} · profile ${key.profileSerial}"
