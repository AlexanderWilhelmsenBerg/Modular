package io.github.alexanderwilhelmsenberg.modular.filesui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import android.os.ParcelFileDescriptor
import io.github.alexanderwilhelmsenberg.modular.filesui.documents.FilesDocumentViewer
import io.github.alexanderwilhelmsenberg.modular.filesui.documents.FileThumbnail
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.FlowPreview

/** Trusted native renderer. Module data is text and typed actions; it is never executable UI code. */
@OptIn(FlowPreview::class)
@Composable
fun FilesPage(
    pageJson: String,
    onAction: (String) -> Unit,
    onOpenDocument: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    documentLoader: suspend (String, String) -> ParcelFileDescriptor? = { _, _ -> null },
    documentCommit: suspend (String) -> Boolean = { false },
    documentCancel: suspend (String) -> Unit = { },
    onScrollState: (String, Int, Int) -> Unit = { _, _, _ -> },
) {
    val page = remember(pageJson) { runCatching { JSONObject(pageJson) }.getOrNull() }
    if (page == null || page.optInt("version") != 1 || page.optString("kind") != "files") {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Files page unavailable") }
        return
    }
    val view = page.optString("view", "browser")
    val actions = remember(pageJson) { page.optJSONArray("actions").objects().map(::PageAction) }
    val items = remember(pageJson) { page.optJSONArray("items").objects() }
    val breadcrumbs = remember(pageJson) { page.optJSONArray("breadcrumbs").objects() }
    val listState = rememberLazyListState()
    val pageKey = page.optString("locationId") + "/" + page.optString("folderId") + "/" + view
    val send: (PageAction, JSONObject?) -> Unit = { action, args ->
        val request = JSONObject().put("version", 1).put("actionId", action.id)
            .put("operationToken", UUID.randomUUID().toString())
        if (args != null) request.put("args", args)
        onAction(request.toString())
    }
    val scrollAction = actions.firstOrNull { it.kind == "scroll" }
    val scrollKey = page.optString("scrollKey")
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestScrollAction by rememberUpdatedState(scrollAction)
    val latestSend by rememberUpdatedState(send)
    val latestOnScrollState by rememberUpdatedState(onScrollState)
    DisposableEffect(pageKey, lifecycleOwner) {
        val capturedAction = scrollAction
        val capturedSend = send
        val flushLocal = {
            if (view == "browser" || view == "search") {
                if (scrollKey.matches(Regex("[0-9a-f]{64}"))) latestOnScrollState(scrollKey,
                    listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            flushLocal()
            latestScrollAction?.let { action ->
                latestSend(action, JSONObject().put("anchor", listState.firstVisibleItemIndex)
                    .put("offset", listState.firstVisibleItemScrollOffset))
            }
        } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            flushLocal()
            capturedAction?.let { action ->
                capturedSend(action, JSONObject().put("anchor", listState.firstVisibleItemIndex)
                    .put("offset", listState.firstVisibleItemScrollOffset))
            }
        }
    }
    LaunchedEffect(pageKey, scrollKey) {
        if (view == "browser" || view == "search") {
            val requestedAnchor = page.optInt("scrollAnchor", 0)
            val validAnchor = requestedAnchor in items.indices
            listState.scrollToItem(if (validAnchor) requestedAnchor else 0,
                if (validAnchor) page.optInt("scrollOffset", 0) else 0)
        }
        if ((view == "browser" || view == "search") && scrollKey.matches(Regex("[0-9a-f]{64}"))) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (anchor, offset) -> latestOnScrollState(scrollKey, anchor, offset) }
        }
    }
    LaunchedEffect(pageKey, scrollAction?.id) {
        if ((view == "browser" || view == "search") && scrollAction != null) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .debounce(250).drop(1).collect { (anchor, offset) ->
                    send(scrollAction, JSONObject().put("anchor", anchor).put("offset", offset))
                }
        }
    }
    LaunchedEffect(view, pageJson) {
        if (view == "progress") {
            delay(900)
            actions.firstOrNull { it.kind == "refresh" }?.let { send(it, null) }
        }
    }
    if (view == "map") {
        FilesStorageMap(page, onAction = { send(PageAction(it), null) }, modifier = modifier)
        return
    }
    if (view == "filters") {
        val apply = actions.firstOrNull { it.kind == "applyFilter" }
        Column(modifier.fillMaxSize()) {
            actions.firstOrNull { it.kind == "back" }?.let { back ->
                TextButton(onClick = { send(back, null) }) { Text("Back to files") }
            }
            if (apply != null) FilesFilterForm(page.optJSONObject("filter") ?: JSONObject(),
                onApply = { send(apply, it) }, modifier = Modifier.weight(1f))
        }
        return
    }
    if (view == "movePicker" || view == "restoreDestination") {
        FilesDestinationPicker(page, onAction = { onAction(it.toString()) }, modifier = modifier)
        return
    }
    if (view == "markdown" || view == "pdf") {
        val document = page.optJSONObject("document")
        val back = actions.firstOrNull { it.kind == "back" }
        val latestBack by rememberUpdatedState(back)
        val latestCommit by rememberUpdatedState(actions.firstOrNull { it.kind == "documentCommit" })
        val latestCancel by rememberUpdatedState(actions.firstOrNull { it.kind == "documentCancel" })
        if (document != null && back != null) {
            FilesDocumentViewer(documentId = document.optString("id"), kind = view,
                baseFingerprint = document.optString("baseFingerprint"), documentLoader = documentLoader,
                onCommit = {
                    val commit = latestCommit
                    if (commit == null) false else documentCommit(JSONObject().put("version", 1)
                        .put("actionId", commit.id).put("operationToken", UUID.randomUUID().toString()).toString())
                }, onCancel = {
                    val cancel = latestCancel
                    if (cancel != null) documentCancel(JSONObject().put("version", 1)
                        .put("actionId", cancel.id).put("operationToken", UUID.randomUUID().toString()).toString())
                }, onClose = { latestBack?.let { send(it, null) } }, modifier = modifier)
        } else Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Document unavailable") }
        return
    }
    var formAction by remember(pageJson) { mutableStateOf<PageAction?>(null) }
    var formValue by remember(pageJson) { mutableStateOf("") }
    val dialog = page.optJSONObject("dialog")
    Surface(modifier.fillMaxSize().testTag("files.page.$view"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(page.optString("title", "Files"), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val trash = page.optJSONObject("trash")
                val trashAction = actions.firstOrNull { it.kind == "trash" }
                if (trashAction != null) {
                    val count = trash?.optLong("fileCount", 0L) ?: 0L
                    val folders = trash?.optLong("emptyFolderCount", 0L) ?: 0L
                    val color = when { count > 20 -> Color(0xFFC33B42); count > 0 || folders > 0 -> Color(0xFF3178CE)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant }
                    TextButton(onClick = { send(trashAction, null) }, modifier = Modifier.testTag("files.trash.badge")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.size(22.dp)) {
                                drawRoundRect(color, topLeft = Offset(size.width * .22f, size.height * .29f),
                                    size = Size(size.width * .56f, size.height * .62f),
                                    cornerRadius = CornerRadius(size.width * .06f))
                                drawLine(color, Offset(size.width * .12f, size.height * .21f),
                                    Offset(size.width * .88f, size.height * .21f), strokeWidth = size.width * .09f)
                                drawLine(color, Offset(size.width * .4f, size.height * .1f),
                                    Offset(size.width * .6f, size.height * .1f), strokeWidth = size.width * .09f)
                            }
                            Column(Modifier.padding(start = 4.dp)) {
                                Text("$count", color = color, style = MaterialTheme.typography.labelLarge)
                                if (trash != null) Text(formatBytes(trash.optLong("knownBytes")), color = color,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            if (breadcrumbs.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    breadcrumbs.forEach { crumb ->
                        val action = PageAction(crumb.optJSONObject("action"))
                        TextButton(onClick = { if (action.id.isNotBlank()) send(action, null) },
                            modifier = Modifier.widthIn(max = 170.dp)) {
                            Text(crumb.optString("name"), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            page.optString("summary").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            page.optString("message").takeIf { it.isNotBlank() }?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Text(it, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
            }
            val commandActions = actions.filterNot { it.kind in setOf("trash", "loadMore", "confirm", "cancel", "scroll", "documentCommit", "documentCancel") }
            if (commandActions.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth().heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    commandActions.forEach { action ->
                        TextButton(onClick = {
                            if (action.input != null) { formAction = action; formValue = action.initial }
                            else send(action, null)
                        }, modifier = Modifier.widthIn(max = 180.dp)) {
                            Text(action.label)
                        }
                    }
                }
            }
            val status = page.optString("state", "ready")
            if (status != "ready") {
                Text(when (status) { "permission" -> "Choose a folder to get started";
                    "unavailable" -> "Storage unavailable. Reconnect it to continue.";
                    "loading" -> "Loading…"; else -> "Unable to load files" }, Modifier.padding(12.dp))
            }
            if (view == "review" || view == "cleanReview" || view == "pasteReview" || view == "archiveReview" || view == "restoreReview") {
                Text(when (view) {
                    "pasteReview" -> "Review sources and destination conflicts"
                    "archiveReview" -> "Review archive contents and extraction choices"
                    "restoreReview" -> "Review restore destinations and conflicts"
                    else -> "Review every affected file before moving to trash"
                }, style = MaterialTheme.typography.titleMedium)
                if (!page.optBoolean("complete", true)) Text("Review incomplete — action unavailable", color = MaterialTheme.colorScheme.error)
            }
            LazyColumn(Modifier.weight(1f).testTag("files.items"), state = listState) {
                if (page.optString("layout") == "grid" && (view == "browser" || view == "search")) {
                    items(items.chunked(2), key = { it.first().optString("id") }) { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { item ->
                                FileRow(item, view, send, onOpenDocument, documentLoader, Modifier.weight(1f), compact = true) { action ->
                                    if (action.input != null) { formAction = action; formValue = action.initial }
                                    else send(action, null)
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                } else {
                    items(items, key = { it.optString("id") }) { item ->
                        FileRow(item, view, send, onOpenDocument, documentLoader) { action ->
                            if (action.input != null) { formAction = action; formValue = action.initial }
                            else send(action, null)
                        }
                        HorizontalDivider()
                    }
                }
                if (page.optString("nextCursor") != "null" && page.optString("nextCursor").isNotBlank()) {
                    item {
                        val more = actions.firstOrNull { it.kind == "loadMore" }
                        if (more != null) TextButton(onClick = { send(more, null) }, Modifier.fillMaxWidth()) { Text("Load more") }
                    }
                }
            }
            if (view == "review" || view == "emptyTrashReview" || view == "cleanReview" || view == "pasteReview" || view == "archiveReview" || view == "restoreReview") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.firstOrNull { it.kind == "cancel" }?.let { action ->
                        OutlinedButton(onClick = { send(action, null) }, Modifier.weight(1f).testTag("files.review.cancel")) { Text("Cancel") }
                    }
                    actions.firstOrNull { it.kind == "confirm" }?.let { action ->
                        Button(onClick = { send(action, null) }, enabled = page.optBoolean("complete", true),
                            modifier = Modifier.weight(1f).testTag("files.review.confirm")) { Text(action.label) }
                    }
                }
            }
        }
    }
    if (formAction != null) {
        val action = formAction!!
        AlertDialog(onDismissRequest = { formValue = ""; formAction = null }, title = { Text(action.label) },
            text = { OutlinedTextField(formValue, { formValue = it.take(512) }, label = { Text(action.input ?: "Value") },
                visualTransformation = if (action.kind == "password") PasswordVisualTransformation() else VisualTransformation.None) },
            confirmButton = { TextButton(onClick = {
                send(action, JSONObject().put(action.input ?: "value", formValue)); formValue = ""; formAction = null
            }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { formValue = ""; formAction = null }) { Text("Cancel") } })
    }
    if (dialog != null) {
        val choices = dialog.optJSONArray("actions").objects().map(::PageAction)
        AlertDialog(onDismissRequest = { choices.firstOrNull { it.kind == "cancel" }?.let { send(it, null) } },
            title = { Text(dialog.optString("title", "Confirm")) },
            text = { Text(dialog.optString("message")) },
            confirmButton = { choices.firstOrNull { it.kind == "confirm" }?.let { action ->
                Button(onClick = { send(action, null) }) { Text(action.label) }
            } },
            dismissButton = { choices.firstOrNull { it.kind == "cancel" }?.let { action ->
                TextButton(onClick = { send(action, null) }) { Text(action.label) }
            } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(item: JSONObject, view: String, send: (PageAction, JSONObject?) -> Unit,
    onOpenDocument: (String, String) -> Unit,
    documentLoader: suspend (String, String) -> ParcelFileDescriptor?,
    modifier: Modifier = Modifier, compact: Boolean = false,
    onCommand: (PageAction) -> Unit) {
    val itemActions = item.optJSONArray("actions").objects().map(::PageAction)
    val primary = itemActions.firstOrNull { it.kind == "open" }
    val kind = item.optString("kind")
    var expanded by remember(item.optString("id")) { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().testTag("files.item.${item.optString("id")}").combinedClickable(onClick = {
        when {
            primary != null -> send(primary, null)
            kind == "markdown" || kind == "pdf" -> onOpenDocument(item.optString("id"), "r")
        }
    }, onLongClick = { expanded = true }).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (item.optString("mimeType").startsWith("image/") && view != "review" && view != "cleanReview") {
            FileThumbnail(item.optString("id"), item.optString("mimeType"), documentLoader,
                modifier = Modifier.size(if (compact) 40.dp else 48.dp), contentDescription = item.optString("name"))
            Spacer(Modifier.size(8.dp))
        } else if (view == "map") {
            Box(Modifier.size(30.dp).background(mapColor(item.optString("mimeType")), RoundedCornerShape(4.dp)))
            Spacer(Modifier.size(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(item.optString("name"), style = MaterialTheme.typography.bodyLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val subtitle = item.optString("subtitle").ifBlank { item.optString("path") }
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            if (view == "review" || view == "cleanReview" || view == "pasteReview" || view == "archiveReview" || view == "restoreReview" || view == "trash" || view == "emptyTrashReview" || view == "properties") {
                val size = if (item.isNull("sizeBytes")) "Size unavailable" else formatBytes(item.optLong("sizeBytes"))
                val modified = date(item, "modifiedAt")
                val created = date(item, "createdAt")
                Text("$size · Modified $modified · Created $created", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (view != "review" && view != "cleanReview" && view != "pasteReview") {
            val secondary = itemActions.filterNot { it.kind == "open" }
            secondary.take(if (compact) 0 else 1).forEach { action ->
                TextButton(onClick = { onCommand(action) }) { Text(action.label) }
            }
            if (secondary.size > if (compact) 0 else 1) {
                Box {
                    TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("files.item.more")) { Text("More") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        secondary.forEach { action ->
                            DropdownMenuItem(text = { Text(action.label) }, onClick = {
                                expanded = false; onCommand(action)
                            })
                        }
                    }
                }
            }
        }
    }
}

private class PageAction(value: JSONObject?) {
    val id: String = value?.optString("id") ?: ""
    val label: String = value?.optString("label") ?: ""
    val kind: String = value?.optString("kind") ?: ""
    val input: String? = value?.optString("input")?.takeIf { it.isNotBlank() }
    val initial: String = value?.optString("initial") ?: ""
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { optJSONObject(it) }

private fun date(item: JSONObject, key: String): String =
    if (!item.has(key) || item.isNull(key)) "Unavailable" else
        runCatching { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.getLong(key))) }
            .getOrDefault("Unavailable")

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do { value /= 1024; unit++ } while (value >= 1024 && unit < units.lastIndex)
    return "%.1f %s".format(value, units[unit])
}

private fun mapColor(mime: String): Color = when {
    mime.startsWith("image/") -> Color(0xFF59A88A)
    mime.startsWith("video/") -> Color(0xFFE08B59)
    mime.startsWith("audio/") -> Color(0xFF9D82C8)
    else -> Color(0xFF5C91C8)
}
