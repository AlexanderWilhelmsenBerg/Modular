package io.github.alexanderwilhelmsenberg.modular.filesui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlinx.coroutines.delay

private data class StorageMapNode(
    val id: String,
    val name: String,
    val path: String,
    val bytes: Long?,
    val partial: Boolean,
    val folder: Boolean,
    val mime: String,
    val actions: List<JSONObject>,
) {
    val type: MapFileType get() = if (folder) MapFileType.FOLDER else fileType(name, mime)
}

private enum class MapFileType(val label: String, val color: Color) {
    FOLDER("Folders", Color(0xFF6484A8)),
    IMAGE("Images", Color(0xFFC48B55)),
    VIDEO("Videos", Color(0xFF8A78BB)),
    AUDIO("Audio", Color(0xFF5D9D91)),
    DOCUMENT("Documents", Color(0xFF5D8EBB)),
    ARCHIVE("Archives", Color(0xFFB17D6B)),
    APP("Apps", Color(0xFF9A88A0)),
    OTHER("Other", Color(0xFF7C8A91)),
}

private fun fileType(name: String, mime: String): MapFileType {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("image/") -> MapFileType.IMAGE
        mime.startsWith("video/") -> MapFileType.VIDEO
        mime.startsWith("audio/") -> MapFileType.AUDIO
        extension in setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz") -> MapFileType.ARCHIVE
        extension in setOf("apk", "apks", "xapk", "aab") -> MapFileType.APP
        mime.startsWith("text/") || extension in setOf("pdf", "md", "txt", "doc", "docx", "odt", "csv") -> MapFileType.DOCUMENT
        else -> MapFileType.OTHER
    }
}

private fun JSONArray?.objects(): List<JSONObject> = this?.let { array ->
    (0 until array.length()).mapNotNull(array::optJSONObject)
} ?: emptyList()

private fun JSONObject.toMapNode(): StorageMapNode? {
    val id = optString("id").takeIf { it.isNotBlank() } ?: return null
    return StorageMapNode(
        id = id,
        name = optString("name", "Unnamed"),
        path = optString("path"),
        bytes = if (isNull("sizeBytes")) null else optLong("sizeBytes").takeIf { it >= 0 },
        partial = optBoolean("partial", false) || optString("complete") == "false",
        folder = optString("kind") == "folder",
        mime = optString("mimeType"),
        actions = optJSONArray("actions").objects(),
    )
}

internal data class StorageMapRect(val x: Float, val y: Float, val width: Float, val height: Float)

/** Binary treemap. Every positive byte weight receives proportional area. */
internal fun storageMapRects(weights: List<Long>, width: Float, height: Float): List<StorageMapRect> {
    if (width <= 0f || height <= 0f || weights.isEmpty()) return emptyList()
    val result = MutableList(weights.size) { StorageMapRect(0f, 0f, 0f, 0f) }
    val values = weights.map { it.coerceAtLeast(0).toDouble() }
    fun partition(start: Int, end: Int, rect: StorageMapRect) {
        if (start >= end) return
        if (end - start == 1) { result[start] = rect; return }
        val total = (start until end).sumOf { values[it] }
        if (total <= 0.0) return
        var left = 0.0
        var split = start + 1
        var best = Double.MAX_VALUE
        for (candidate in start + 1 until end) {
            left += values[candidate - 1]
            val difference = abs(left - total / 2)
            if (difference < best) { best = difference; split = candidate }
        }
        val firstWeight = (start until split).sumOf { values[it] }
        val fraction = (firstWeight / total).toFloat().coerceIn(0f, 1f)
        if (rect.width >= rect.height) {
            val firstWidth = rect.width * fraction
            partition(start, split, StorageMapRect(rect.x, rect.y, firstWidth, rect.height))
            partition(split, end, StorageMapRect(rect.x + firstWidth, rect.y,
                rect.width - firstWidth, rect.height))
        } else {
            val firstHeight = rect.height * fraction
            partition(start, split, StorageMapRect(rect.x, rect.y, rect.width, firstHeight))
            partition(split, end, StorageMapRect(rect.x, rect.y + firstHeight,
                rect.width, rect.height - firstHeight))
        }
    }
    partition(0, weights.size, StorageMapRect(0f, 0f, width, height))
    return result
}

/** Native storage map. Unknown sizes stay outside the proportional rectangle area. */
@Composable
fun FilesStorageMap(
    page: JSONObject,
    onAction: (JSONObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nodes = remember(page.toString()) {
        (page.optJSONArray("mapNodes") ?: page.optJSONArray("items")).objects()
            .mapNotNull(JSONObject::toMapNode)
    }
    val known = remember(nodes) { nodes.filter { (it.bytes ?: 0) > 0 }.sortedByDescending { it.bytes } }
    val unscaled = remember(nodes) { nodes.filter { (it.bytes ?: 0) <= 0 } }
    var selectedId by remember(page.optString("mapPath"), nodes) { mutableStateOf<String?>(null) }
    val selected = nodes.firstOrNull { it.id == selectedId }
    val actions = page.optJSONArray("actions").objects()
    val path = page.optString("mapPath").ifBlank { page.optString("summary").substringBefore(" · ") }
    val partial = nodes.any { it.partial } || page.optBoolean("mapComplete", true).not()
    val scannedBytes = page.optLong("mapTotalBytes", -1L).takeIf { it >= 0L }
    val refresh = actions.firstOrNull { it.optString("kind") == "refresh" &&
        it.optString("label").contains("map", ignoreCase = true) }
    LaunchedEffect(page.toString(), page.optBoolean("mapCalculating")) {
        if (page.optBoolean("mapCalculating") && refresh != null) {
            delay(1_200)
            onAction(refresh)
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("files-storage-map"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Storage map", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.firstOrNull { it.optString("label") == "Back to files" }?.let { action ->
                TextButton(onClick = { onAction(action) }) { Text("Files") }
            }
            actions.firstOrNull { it.optString("label") == "Up" }?.let { action ->
                TextButton(onClick = { onAction(action) }) { Text("Up") }
            }
            refresh?.let { action ->
                TextButton(onClick = { onAction(action) }) { Text("Refresh") }
            }
            if (page.optBoolean("mapCalculating")) {
                actions.firstOrNull { it.optString("kind") == "cancelMap" }?.let { action ->
                    TextButton(onClick = { onAction(action) }) { Text("Cancel analysis") }
                }
            }
            actions.firstOrNull { it.optString("kind") == "trash" }?.let { action ->
                val count = page.optJSONObject("trash")?.optLong("fileCount", 0L) ?: 0L
                val color = filesTrashColor(count,
                    page.optJSONObject("trash")?.optLong("emptyFolderCount", 0L) ?: 0L)
                TextButton(onClick = { onAction(action) }) { Text("Trash $count", color = color) }
            }
        }
        FlowRow(Modifier.testTag("files-storage-map-path"),
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            val parts = path.split('/').filter { it.isNotBlank() }.takeLast(8)
            if (parts.isEmpty()) Text(path, style = MaterialTheme.typography.bodySmall)
            parts.forEachIndexed { index, part ->
                if (index > 0) Text("›", style = MaterialTheme.typography.bodySmall)
                Text(part, style = MaterialTheme.typography.bodySmall,
                    color = if (index == parts.lastIndex) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("Rectangle area shows scanned logical bytes. ${if (partial) "Some folders are still calculating or incomplete." else "Tap a block for details."}",
            style = MaterialTheme.typography.bodySmall)
        Text("Scanned: ${scannedBytes?.let(::formatStorageMapBytes) ?: if (page.optBoolean("mapCalculating")) "calculating" else "unavailable"} · Volume capacity is separate",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (known.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(240.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center) {
                Text("No measured file sizes yet", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth().height(280.dp)
                .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                val rects = remember(known, maxWidth, maxHeight) {
                    storageMapRects(known.map { it.bytes ?: 0 }, maxWidth.value, maxHeight.value)
                }
                known.forEachIndexed { index, node ->
                    val rect = rects[index]
                    if (rect.width > 0f && rect.height > 0f) Box(
                        Modifier.offset(x = rect.x.dp, y = rect.y.dp)
                            .size(rect.width.dp, rect.height.dp)
                            .background(node.type.color)
                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.background))
                            .clickable { selectedId = node.id }
                            .testTag("files-map-tile:${node.id}"),
                    ) {
                        if (rect.width >= 54f && rect.height >= 32f) {
                            Text(node.name, Modifier.padding(5.dp),
                                color = if (node.type.color.luminance() > 0.179f) Color.Black else Color.White,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            nodes.map { it.type }.distinct().forEach { type ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(type.color, RoundedCornerShape(2.dp)))
                    Text(" ${type.label}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (selected != null) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().testTag("files-map-detail")) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(selected.name, style = MaterialTheme.typography.titleSmall)
                    Text(selected.path, style = MaterialTheme.typography.bodySmall)
                    Text(selected.bytes?.let(::formatStorageMapBytes) ?: "Size unavailable",
                        style = MaterialTheme.typography.bodySmall)
                    if (selected.partial) Text("Partial coverage", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        selected.actions.firstOrNull { it.optString("label") == "Zoom" }?.let { action ->
                            OutlinedButton(onClick = { onAction(action) }) { Text("Zoom in") }
                        }
                        selected.actions.firstOrNull { it.optString("label") == "Open" }?.let { action ->
                            OutlinedButton(onClick = { onAction(action) }) {
                                Text(if (selected.folder) "Open folder" else "Open file")
                            }
                        }
                    }
                }
            }
        }
        Text("Largest items", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(known.take(30), key = { it.id }) { node ->
                Row(Modifier.fillMaxWidth().clickable { selectedId = node.id }.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(node.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatStorageMapBytes(node.bytes ?: 0), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (unscaled.isNotEmpty()) item {
                Text("Unknown or empty size (${unscaled.size})", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp))
            }
            items(unscaled.take(30), key = { "unknown:${it.id}" }) { node ->
                Row(Modifier.fillMaxWidth().clickable { selectedId = node.id }.padding(vertical = 6.dp)) {
                    Text(node.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (node.bytes == 0L) "Empty" else "Unknown",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            actions.firstOrNull { it.optString("kind") == "loadMore" }?.let { action ->
                item { TextButton(onClick = { onAction(action) }) { Text("Load more") } }
            }
        }
    }
}

private fun formatStorageMapBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var amount = bytes.toDouble()
    var unit = -1
    do { amount /= 1024; unit++ } while (amount >= 1024 && unit < units.lastIndex)
    return "%.1f %s".format(amount, units[unit])
}
