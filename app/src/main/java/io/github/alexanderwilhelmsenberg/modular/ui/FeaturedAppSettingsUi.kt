package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.home.HomeFeaturedState
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsResultParcel
import java.util.Locale
import kotlinx.coroutines.launch

/** Core's generic picker and pin manager for the currently selected featured provider. */
@Composable
fun FeaturedAppSettingsUi(
    catalog: List<LaunchableApp>,
    featured: HomeFeaturedState?,
    onSetPinned: suspend (AppKey, Boolean) -> Result<Unit>,
    onReorderPins: suspend (List<AppKey>) -> Result<Unit>,
    onRefresh: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val byKey = remember(catalog) { catalog.associateBy { it.key } }
    val duplicateKeys = remember(catalog) {
        catalog.groupBy { it.label.lowercase(Locale.ROOT) }.values
            .filter { it.size > 1 }.flatMap { group -> group.map { it.key } }.toSet()
    }

    fun save(action: suspend () -> Result<Unit>) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val result = runCatching { action() }.getOrElse { Result.failure(it) }
            busy = false
            result.onFailure { error = it.message ?: "Could not save pins. Try again." }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pinned apps", style = MaterialTheme.typography.titleMedium)
        if (featured == null) {
            Text("Loading saved pins…", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { error = null; onRefresh() }) { Text("Retry") }
        } else {
            TextButton(enabled = !busy, onClick = { error = null; onRefresh() }) { Text("Refresh pins") }
            if (featured.pinnedKeys.isEmpty()) Text(
                "No apps pinned yet. Pins work without Usage Access.",
                style = MaterialTheme.typography.bodyMedium,
            )
            featured.pinnedKeys.forEachIndexed { index, key ->
                val app = byKey[key]
                val unavailable = key in featured.unavailablePinnedKeys
                Column {
                    Text(
                        if (unavailable) "${app?.label ?: key.packageName} (unavailable)" else app?.label ?: key.packageName,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (unavailable || key in duplicateKeys) Text(
                        identityHint(key), style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = !busy && index > 0, onClick = {
                            save { onReorderPins(featured.pinnedKeys.toMutableList().apply { add(index - 1, removeAt(index)) }) }
                        }) { Text("Up") }
                        TextButton(enabled = !busy && index < featured.pinnedKeys.lastIndex, onClick = {
                            save { onReorderPins(featured.pinnedKeys.toMutableList().apply { add(index + 1, removeAt(index)) }) }
                        }) { Text("Down") }
                        TextButton(enabled = !busy, onClick = { save { onSetPinned(key, false) } }) { Text("Remove") }
                    }
                }
            }
            OutlinedButton(
                enabled = !busy && featured.pinnedKeys.size < 50,
                onClick = { pickerOpen = !pickerOpen },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (pickerOpen) "Close app picker" else "Pin apps") }
            if (pickerOpen) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Find an app") },
                    modifier = Modifier.fillMaxWidth().testTag("featured-app-picker-search"),
                    singleLine = true,
                )
                val choices = remember(catalog, featured.pinnedKeys, query) {
                    catalog.asSequence()
                        .filter { it.enabled && it.key !in featured.pinnedKeys &&
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
                        ) { Text(if (app.key in duplicateKeys) "${app.label} · ${identityHint(app.key)}" else app.label) }
                    }
                    if (choices.isEmpty()) item { Text("No matching apps.") }
                }
            }
            if (featured.featureState == FeaturedAppsResultParcel.STATE_PERMISSION_REQUIRED) Text(
                "Usage Access is off. Saved pins still appear on Home.",
                style = MaterialTheme.typography.bodySmall,
            ) else if (featured.featureState == FeaturedAppsResultParcel.STATE_NO_HISTORY) Text(
                "No usage history yet. Saved pins still appear on Home.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun identityHint(key: AppKey): String =
    "${key.packageName}/${key.activityName} · profile ${key.profileSerial}"
