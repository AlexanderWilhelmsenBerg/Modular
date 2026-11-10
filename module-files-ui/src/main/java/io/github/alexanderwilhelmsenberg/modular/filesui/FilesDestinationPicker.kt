package io.github.alexanderwilhelmsenberg.modular.filesui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Folder-only destination view. The module decides which locations and actions are valid. */
@Composable
fun FilesDestinationPicker(page: JSONObject, onAction: (JSONObject) -> Unit, modifier: Modifier = Modifier) {
    val actions = page.optJSONArray("actions").objectsForDestination()
    val folders = page.optJSONArray("items").objectsForDestination()
    fun send(action: JSONObject) {
        if (action.optString("id").isBlank()) return
        onAction(JSONObject().put("version", 1).put("actionId", action.optString("id"))
            .put("operationToken", UUID.randomUUID().toString()))
    }
    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose destination", style = MaterialTheme.typography.titleLarge)
        page.optString("currentFolderPath").takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        page.optString("message").takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.firstOrNull { it.optString("kind") == "back" || it.optString("kind") == "up" }?.let { action ->
                OutlinedButton(onClick = { send(action) }) { Text(action.optString("label", "Up")) }
            }
            actions.firstOrNull { it.optString("kind") == "locations" }?.let { action ->
                OutlinedButton(onClick = { send(action) }) { Text(action.optString("label", "Locations")) }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(folders, key = { it.optString("id") }) { folder ->
                val nav = folder.optJSONArray("actions").objectsForDestination()
                    .firstOrNull { it.optString("kind") == "nav" || it.optString("kind") == "open" }
                Column(Modifier.fillMaxWidth().clickable(enabled = nav != null) { nav?.let(::send) }
                    .padding(vertical = 12.dp)) {
                    Text(folder.optString("name"), style = MaterialTheme.typography.bodyLarge)
                    folder.optString("path").takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.firstOrNull { it.optString("kind") == "cancel" }?.let { action ->
                OutlinedButton(onClick = { send(action) }, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
            actions.firstOrNull { it.optString("kind") == "chooseDestination" }?.let { action ->
                Button(onClick = { send(action) }, enabled = page.optBoolean("destinationWritable", true),
                    modifier = Modifier.weight(1f)) { Text("Use this folder") }
            }
        }
    }
}

private fun JSONArray?.objectsForDestination(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { optJSONObject(it) }
