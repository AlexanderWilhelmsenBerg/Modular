package io.github.alexanderwilhelmsenberg.modular.filesui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.Instant

@Composable
internal fun FilesEventLogPage(page: JSONObject, send: (JSONObject) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val actions = page.optJSONArray("actions")
    fun action(kind: String) = (0 until (actions?.length() ?: 0)).mapNotNull { actions?.optJSONObject(it) }
        .firstOrNull { it.optString("kind") == kind }
    val records = page.optJSONArray("items")
    val entries = (0 until (records?.length() ?: 0)).mapNotNull { records?.optJSONObject(it) }
    Surface(modifier.fillMaxSize().testTag("files.events.page"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                action("back")?.let { TextButton(onClick = { send(it) }) { Text("Back") } }
                Text("Event log", Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
            }
            Text("Stored on this device. Logs may contain filenames; review before sharing.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Modular Files event log", page.optString("eventLogText")))
                }, modifier = Modifier.testTag("files.events.copy")) { Text("Copy") }
                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, page.optString("eventLogText"))
                    context.startActivity(Intent.createChooser(share, "Share Files event log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, modifier = Modifier.testTag("files.events.share")) { Text("Share") }
                action("refresh")?.let { TextButton(onClick = { send(it) }) { Text("Refresh") } }
                action("clearEventLog")?.let { TextButton(onClick = { send(it) },
                    modifier = Modifier.testTag("files.events.clear")) { Text("Clear") } }
            }
            FilesActiveOperation(page, send)
            page.optString("message").takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            val detail = page.optJSONObject("eventDetail")
            LazyColumn(Modifier.weight(1f).testTag("files.events.entries")) {
                if (detail != null) item { EventText(detail, detailed = true) }
                else {
                    if (entries.isEmpty()) item { Text("No events recorded.", Modifier.padding(vertical = 16.dp)) }
                    items(entries, key = { it.optString("id") }) { entry ->
                        Column(Modifier.fillMaxWidth().clickable {
                            entry.optJSONArray("actions")?.optJSONObject(0)?.let(send)
                        }.padding(vertical = 8.dp)) { EventText(entry, detailed = false) }
                        HorizontalDivider()
                    }
                    action("loadMore")?.let { more -> item { TextButton(onClick = { send(more) }) { Text("More events") } } }
                }
            }
        }
    }
}

@Composable
private fun EventText(entry: JSONObject, detailed: Boolean) {
    Text("${entry.optString("level")} · ${entry.optString("event")}", style = MaterialTheme.typography.titleSmall,
        color = if (entry.optString("level") == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    Text(Instant.ofEpochMilli(entry.optLong("at")).toString(), style = MaterialTheme.typography.bodySmall)
    Text(entry.optString("message"), style = MaterialTheme.typography.bodyMedium)
    if (detailed) {
        entry.optString("operationId", "").takeIf { it.isNotBlank() }?.let { Text("Operation: $it") }
        Text(entry.optString("details", ""), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun FilesActiveOperation(page: JSONObject, send: (JSONObject) -> Unit) {
    val operation = page.optJSONObject("activeOperation") ?: return
    val actions = page.optJSONArray("actions")
    Column(Modifier.fillMaxWidth().testTag("files.operation.active")) {
        Text(operation.optString("status"), style = MaterialTheme.typography.bodySmall)
        Row {
            for (index in 0 until (actions?.length() ?: 0)) {
                val action = actions?.optJSONObject(index) ?: continue
                if (action.optString("kind") in setOf("showOperation", "cancelOperation")) {
                    TextButton(onClick = { send(action) }, enabled = action.optString("kind") != "cancelOperation" ||
                        !operation.optBoolean("cancelRequested")) { Text(action.optString("label")) }
                }
            }
        }
    }
}
