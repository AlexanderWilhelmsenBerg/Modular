package io.github.alexanderwilhelmsenberg.modular.filesui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import android.os.ParcelFileDescriptor
import android.provider.Settings
import io.github.alexanderwilhelmsenberg.modular.filesui.documents.FilesDocumentViewer
import io.github.alexanderwilhelmsenberg.modular.filesui.documents.FileThumbnail
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt

/** Trusted native renderer. Module data is text and typed actions; it is never executable UI code. */
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
    val context = LocalContext.current
    val motionEnabled = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }.getOrDefault(true)
    }
    var lastBrowserPage by remember { mutableStateOf<JSONObject?>(null) }
    var pageBounds by remember { mutableStateOf(Rect.Zero) }
    var flyingFolder by remember { mutableStateOf<FolderFlight?>(null) }
    val flightProgress = remember { Animatable(0f) }
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val pageKey = page.optString("locationId") + "/" + page.optString("folderId") + "/" + view
    val send: (PageAction, JSONObject?) -> Unit = { action, args ->
        val request = JSONObject().put("version", 1).put("actionId", action.id)
            .put("operationToken", UUID.randomUUID().toString())
        if (args != null) request.put("args", args)
        onAction(request.toString())
    }
    val openFolder: (JSONObject, PageAction, Rect) -> Unit = { item, action, bounds ->
        if (flyingFolder == null) {
            flyingFolder = FolderFlight(item.optString("id"), item.optString("name"), item.optString("subtitle"),
                FilesFolderAppearance.fromJson(item.optJSONObject("folderAppearance")),
                Rect(bounds.left - pageBounds.left, bounds.top - pageBounds.top,
                    bounds.right - pageBounds.left, bounds.bottom - pageBounds.top), action)
        }
    }
    LaunchedEffect(flyingFolder) {
        val flight = flyingFolder ?: return@LaunchedEffect
        flightProgress.snapTo(0f)
        if (motionEnabled) flightProgress.animateTo(1f, tween(210)) else flightProgress.snapTo(1f)
        send(flight.action, null)
        delay(500)
        if (flyingFolder == flight) flyingFolder = null
    }
    LaunchedEffect(pageJson) {
        if (view == "browser" || view == "search") lastBrowserPage = page
    }
    val scrollKey = page.optString("scrollKey")
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnScrollState by rememberUpdatedState(onScrollState)
    DisposableEffect(pageKey, lifecycleOwner) {
        val flushLocal = {
            if (view == "browser" || view == "search") {
                if (scrollKey.matches(Regex("[0-9a-f]{64}"))) latestOnScrollState(scrollKey,
                    listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            flushLocal()
        } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            flushLocal()
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
    LaunchedEffect(view, pageJson) {
        if (view == "progress" || page.optBoolean("sizesCalculating")) {
            val refresh = actions.firstOrNull {
                it.kind == if (view == "progress") "refresh" else "sizeProgress"
            }
            while (refresh != null) {
                delay(900)
                send(refresh, null)
            }
        }
    }
    if (view == "map") {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            FilesStorageMap(page, onAction = { send(PageAction(it), null) })
        }
        return
    }
    if (view == "filters") {
        val apply = actions.firstOrNull { it.kind == "applyFilter" }
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                actions.firstOrNull { it.kind == "back" }?.let { back ->
                    TextButton(onClick = { send(back, null) }) { Text("Back to files") }
                }
                if (apply != null) FilesFilterForm(page.optJSONObject("filter") ?: JSONObject(),
                    onApply = { send(apply, it) }, modifier = Modifier.weight(1f))
            }
        }
        return
    }
    if (view == "movePicker" || view == "restoreDestination") {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            FilesDestinationPicker(page, onAction = { onAction(it.toString()) })
        }
        return
    }
    if (view == "markdown" || view == "pdf") {
        val document = page.optJSONObject("document")
        val back = actions.firstOrNull { it.kind == "back" }
        val latestBack by rememberUpdatedState(back)
        val latestCommit by rememberUpdatedState(actions.firstOrNull { it.kind == "documentCommit" })
        val latestCancel by rememberUpdatedState(actions.firstOrNull { it.kind == "documentCancel" })
        if (document != null && back != null) {
            Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                    }, onClose = { latestBack?.let { send(it, null) } })
            }
        } else Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Document unavailable") }
        }
        return
    }
    if ((view == "clean" || view == "progress") && page.optString("presentation") == "sheet" &&
        (view == "clean" || page.optString("progressFor") == "clean")) {
        FilesCleanSheet(page, lastBrowserPage, items, actions, send, onOpenDocument,
            documentLoader, modifier)
        return
    }
    if (view == "folderAppearance") {
        FilesFolderAppearanceEditor(page, actions, send, modifier)
        return
    }
    var formAction by remember(pageKey) { mutableStateOf<PageAction?>(null) }
    var formValue by remember(pageKey) { mutableStateOf("") }
    var newFileAction by remember(pageKey) { mutableStateOf<PageAction?>(null) }
    var newFileName by remember(pageKey) { mutableStateOf("") }
    var newFileExtension by remember(pageKey) { mutableStateOf("txt") }
    var searchQuery by remember(pageKey) { mutableStateOf(page.optString("query")) }
    val keyboardController = LocalSoftwareKeyboardController.current
    var createMenuExpanded by remember(pageKey) { mutableStateOf(false) }
    var pageMenuExpanded by remember(pageKey) { mutableStateOf(false) }
    var headerVisible by remember(pageKey) { mutableStateOf(false) }
    var contentVisible by remember(pageKey) { mutableStateOf(view != "browser" && view != "search") }
    LaunchedEffect(pageKey) {
        flyingFolder = null
        headerVisible = true
        if (motionEnabled && (view == "browser" || view == "search")) delay(100)
        contentVisible = true
    }
    val contentAlpha by androidx.compose.animation.core.animateFloatAsState(
        if (contentVisible) 1f else 0f, animationSpec = tween(if (motionEnabled) 150 else 0), label = "Folder contents")
    val dialog = page.optJSONObject("dialog")
    Box(modifier.fillMaxSize().onGloballyPositioned { pageBounds = it.boundsInRoot() }) {
    Surface(Modifier.fillMaxSize().testTag("files.page.$view"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val currentFolder = page.optJSONObject("currentFolder")
                AnimatedVisibility(visible = headerVisible && currentFolder != null,
                    enter = if (motionEnabled) slideInVertically(tween(170)) { it * 2 } + fadeIn(tween(170)) else fadeIn(tween(0))) {
                    FilesFolderGlyph(FilesFolderAppearance.fromJson(currentFolder),
                        Modifier.size(40.dp).padding(end = 5.dp).testTag("files.folder.header"))
                }
                Text(page.optString("title", "Files"), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                actions.firstOrNull { it.kind == "back" }?.let { back ->
                    TextButton(onClick = { send(back, null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (view == "browser" || view == "search") "Up" else "Back")
                    }
                }
                actions.firstOrNull { it.kind == "trash" }?.let { trashAction ->
                    val trash = page.optJSONObject("trash")
                    val count = trash?.optLong("fileCount", 0L) ?: 0L
                    TextButton(onClick = { send(trashAction, null) },
                        modifier = Modifier.testTag("files.trash.badge")) {
                        val color = filesTrashColor(count, trash?.optLong("emptyFolderCount", 0L) ?: 0L)
                        Canvas(Modifier.size(20.dp)) {
                            drawRoundRect(color, Offset(size.width * .22f, size.height * .29f),
                                Size(size.width * .56f, size.height * .62f), CornerRadius(size.width * .06f))
                            drawLine(color, Offset(size.width * .12f, size.height * .21f),
                                Offset(size.width * .88f, size.height * .21f), size.width * .09f)
                            drawLine(color, Offset(size.width * .4f, size.height * .1f),
                                Offset(size.width * .6f, size.height * .1f), size.width * .09f)
                        }
                        Text(" $count", color = color)
                    }
                }
                if (view == "overview" || view == "browser" || view == "search") {
                    val headerActions = actions.filterNot { it.kind in setOf("back", "loadMore", "confirm", "cancel", "scroll", "sizeProgress", "documentCommit", "documentCancel", "create", "createFile", "search", "trash") }
                    Box {
                        FilesMoreButton(pageMenuExpanded, "files.action.more") { pageMenuExpanded = true }
                        DropdownMenu(expanded = pageMenuExpanded, onDismissRequest = { pageMenuExpanded = false }) {
                            headerActions.forEach { action ->
                                DropdownMenuItem(text = { Text(action.label) },
                                    modifier = Modifier.testTag("files.action.${action.kind}"), onClick = {
                                        pageMenuExpanded = false
                                        if (action.input != null) { formAction = action; formValue = action.initial }
                                        else send(action, null)
                                    })
                            }
                        }
                    }
                }
            }
            if (view == "overview" || view == "browser" || view == "search") {
                val search = actions.firstOrNull { it.kind == "search" }
                val createFolder = actions.firstOrNull { it.kind == "create" }
                val createFile = actions.firstOrNull { it.kind == "createFile" }
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it.take(512) },
                        modifier = Modifier.weight(1f).testTag("files.search.input"),
                        singleLine = true, placeholder = { Text("Search files") },
                        leadingIcon = {
                            val color = MaterialTheme.colorScheme.onSurfaceVariant
                            Canvas(Modifier.size(22.dp)) {
                                drawCircle(color, size.width * .25f, Offset(size.width * .41f, size.height * .41f),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(size.width * .08f))
                                drawLine(color, Offset(size.width * .60f, size.height * .60f),
                                    Offset(size.width * .86f, size.height * .86f), size.width * .08f)
                            }
                        },
                        trailingIcon = { if (search != null) TextButton(onClick = {
                            keyboardController?.hide(); send(search, JSONObject().put("query", searchQuery.trim()))
                        }, modifier = Modifier.testTag("files.search.submit")) { Text("Go") } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboardController?.hide(); search?.let { send(it, JSONObject().put("query", searchQuery.trim())) }
                        }))
                    if (createFolder != null || createFile != null) Box {
                        Surface(onClick = { createMenuExpanded = true },
                            modifier = Modifier.size(48.dp).testTag("files.create.menu"), shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(contentAlignment = Alignment.Center) { Text("+", style = MaterialTheme.typography.headlineMedium) }
                        }
                        DropdownMenu(expanded = createMenuExpanded, onDismissRequest = { createMenuExpanded = false }) {
                            createFolder?.let { action -> DropdownMenuItem(text = { Text("Folder") },
                                modifier = Modifier.testTag("files.create.folder"), onClick = {
                                    createMenuExpanded = false; formAction = action; formValue = action.initial
                                }) }
                            createFile?.let { action -> DropdownMenuItem(text = { Text("File") },
                                modifier = Modifier.testTag("files.create.file"), onClick = {
                                    createMenuExpanded = false; newFileAction = action
                                }) }
                        }
                    }
                }
            }
            if (view == "overview") {
                val setupActions = actions.filter { it.kind == "setup" }
                if (setupActions.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    setupActions.forEach { action ->
                        TextButton(onClick = { send(action, null) },
                            modifier = Modifier.testTag("files.action.setup")) { Text(action.label) }
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
            page.optString("summary").takeIf { it.isNotBlank() && view != "settings" }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (view == "info") Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
            }
            page.optString("message").takeIf { it.isNotBlank() }?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Text(it, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
            }
            val commandActions = actions.filterNot { it.kind in setOf("trash", "loadMore", "confirm", "cancel", "scroll", "documentCommit", "documentCancel", "back") }
            if (commandActions.isNotEmpty() && view !in setOf("overview", "browser", "search")) {
                val primaryKinds = if (view == "overview") setOf("setup", "settings") else
                    if (view == "browser" || view == "search") setOf("search", "clean", "map", "create") else
                        setOf("scan", "review", "refresh", "setup")
                val primaryActions = if (view == "settings") commandActions.filter { it.kind != "info" }
                    else commandActions.filter { it.kind in primaryKinds }.take(4)
                val overflowActions = commandActions.filterNot { it in primaryActions }
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (primaryActions.isNotEmpty()) {
                        val largeSettingsText = view == "settings" && density.fontScale >= 1.3f
                        Column(Modifier.weight(1f)
                            .then(if (view == "settings") Modifier.verticalScroll(rememberScrollState()) else Modifier),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            primaryActions.chunked(if (largeSettingsText) 1 else 2).forEach { pair ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEach { action ->
                                        FilesActionTile(action, Modifier.weight(1f)) {
                                            if (action.input != null) { formAction = action; formValue = action.initial }
                                            else send(action, null)
                                        }
                                    }
                                    if (pair.size == 1 && !largeSettingsText) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    if (overflowActions.isNotEmpty()) {
                        Box {
                            FilesMoreButton(pageMenuExpanded, "files.action.more") { pageMenuExpanded = true }
                            DropdownMenu(expanded = pageMenuExpanded, onDismissRequest = { pageMenuExpanded = false },
                                modifier = Modifier.animateContentSize()) {
                                overflowActions.forEach { action ->
                                    DropdownMenuItem(text = { Text(if (action.kind == "info") "ⓘ  Info" else action.label) },
                                        modifier = Modifier.testTag(if (action.kind == "info") "files.action.info" else "files.action.${action.kind}"),
                                        onClick = {
                                            pageMenuExpanded = false
                                            if (action.input != null) { formAction = action; formValue = action.initial }
                                            else send(action, null)
                                        })
                                }
                            }
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
            LazyColumn(Modifier.weight(1f).alpha(contentAlpha).testTag("files.items"), state = listState) {
                items(items, key = { "item:${it.optString("id")}" }) { item ->
                    if (view == "overview" || view == "browser" || view == "search") {
                        FilesEntryRow(item, page.optString("layout") == "details",
                            flyingFolder?.id == item.optString("id"),
                            onOpenFolder = { action, bounds -> openFolder(item, action, bounds) },
                            onOpenFile = { action -> send(action, null) },
                            onCommand = { action ->
                                if (action.input != null) { formAction = action; formValue = action.initial }
                                else send(action, null)
                            }, onOpenDocument = onOpenDocument, documentLoader = documentLoader)
                    } else {
                        FileRow(item, view, send, onOpenDocument, documentLoader) { action ->
                            if (action.input != null) { formAction = action; formValue = action.initial }
                            else send(action, null)
                        }
                    }
                    HorizontalDivider()
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
    flyingFolder?.let { flight ->
        val fraction = flightProgress.value
        val targetLeft = with(density) { 12.dp.toPx() }
        val targetTop = with(density) { 8.dp.toPx() }
        val targetSize = with(density) { 36.dp.toPx() }
        val left = flight.bounds.left + (targetLeft - flight.bounds.left) * fraction
        val top = flight.bounds.top + (targetTop - flight.bounds.top) * fraction
        val width = flight.bounds.width + (targetSize - flight.bounds.width) * fraction
        val height = flight.bounds.height + (targetSize - flight.bounds.height) * fraction
        FilesFolderGlyph(flight.appearance, Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { width.toDp() }, with(density) { height.toDp() }))
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
    if (newFileAction != null) {
        val action = newFileAction!!
        AlertDialog(onDismissRequest = { newFileAction = null }, title = { Text("New file") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newFileName, { newFileName = it.take(255) },
                        label = { Text("Name") }, singleLine = true, modifier = Modifier.testTag("files.create.name"))
                    OutlinedTextField(newFileExtension, { newFileExtension = it.take(32) },
                        label = { Text("Extension") }, singleLine = true, modifier = Modifier.testTag("files.create.extension"))
                }
            }, confirmButton = { TextButton(onClick = {
                send(action, JSONObject().put("name", newFileName.trim())
                    .put("extension", newFileExtension.trim().removePrefix(".")))
                newFileAction = null; newFileName = ""; newFileExtension = "txt"
            }, enabled = newFileName.isNotBlank()) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newFileAction = null }) { Text("Cancel") } })
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

@Composable
private fun FilesActionTile(action: PageAction, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 68.dp)
        .testTag("files.action.${action.kind}")
        .semantics { contentDescription = action.label }, shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center) {
            Text(action.label, style = MaterialTheme.typography.labelLarge,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (action.input != null && action.initial.isNotBlank()) {
                Text("Current: ${action.initial}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun FilesMoreButton(expanded: Boolean, tag: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val motionEnabled = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }.getOrDefault(true)
    }
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        if (expanded) 90f else 0f, animationSpec = tween(if (motionEnabled) 160 else 0), label = "More menu")
    Surface(onClick = onClick, modifier = Modifier.size(52.dp).testTag(tag)
        .semantics { contentDescription = "More options" }, shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(contentAlignment = Alignment.Center) {
            Text("⋮", modifier = Modifier.rotate(rotation), style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilesEntryRow(item: JSONObject, details: Boolean, sourceHidden: Boolean,
    onOpenFolder: (PageAction, Rect) -> Unit, onOpenFile: (PageAction) -> Unit,
    onCommand: (PageAction) -> Unit, onOpenDocument: (String, String) -> Unit,
    documentLoader: suspend (String, String) -> ParcelFileDescriptor?) {
    val id = item.optString("id")
    val kind = item.optString("kind")
    val isFolder = kind == "folder" || kind == "location"
    val actions = item.optJSONArray("actions").objects().map(::PageAction)
    val open = actions.firstOrNull { it.kind == "open" }
    val secondary = actions.filterNot { it.kind == "open" }
    val glyphSize = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() * 1.5f }
    var expanded by remember(id) { mutableStateOf(false) }
    var iconBounds by remember(id) { mutableStateOf(Rect.Zero) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .testTag(if (isFolder) "files.folder.row:$id" else "files.item.$id")
        .combinedClickable(onClick = {
            when {
                open != null && isFolder -> onOpenFolder(open, iconBounds)
                open != null -> onOpenFile(open)
                kind == "markdown" || kind == "pdf" -> onOpenDocument(id, "r")
            }
        }, onLongClick = { if (secondary.isNotEmpty()) expanded = true })
        .padding(horizontal = 4.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isFolder) {
            FilesFolderGlyph(FilesFolderAppearance.fromJson(item.optJSONObject("folderAppearance")),
                Modifier.size(glyphSize).onGloballyPositioned { iconBounds = it.boundsInRoot() }
                    .alpha(if (sourceHidden) 0f else 1f).testTag("files.folder.icon:$id"))
        } else if (item.optString("mimeType").startsWith("image/")) {
            FileThumbnail(id, item.optString("mimeType"), documentLoader, Modifier.size(glyphSize),
                contentDescription = item.optString("name"))
        } else {
            Box(Modifier.size(glyphSize), contentAlignment = Alignment.Center) {
                Text("▤", style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(item.optString("name"), Modifier.weight(1f).padding(start = 10.dp),
            style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (details) {
            val size = if (item.isNull("sizeBytes") || !item.has("sizeBytes")) "—"
                else formatBytes(item.optLong("sizeBytes"))
            Text(size, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Box {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                secondary.forEach { action ->
                    DropdownMenuItem(text = { Text(action.label) },
                        modifier = Modifier.testTag("files.item.action.${action.kind}"), onClick = {
                            expanded = false; onCommand(action)
                        })
                }
            }
        }
    }
}

@Composable
private fun FilesFolderAppearanceEditor(page: JSONObject, actions: List<PageAction>,
    send: (PageAction, JSONObject?) -> Unit, modifier: Modifier) {
    val appearance = FilesFolderAppearance.fromJson(page.optJSONObject("folderAppearance"))
    val options = page.optJSONObject("folderAppearanceOptions")
    val icons = options?.optJSONArray("icons").objects()
    val colors = options?.optJSONArray("colors").objects()
    val iconAction = actions.firstOrNull { it.kind == "setFolderIcon" }
    val colorAction = actions.firstOrNull { it.kind == "setFolderColor" }
    Surface(modifier.fillMaxSize().testTag("files.folder.appearance"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(page.optString("title", "Folder appearance"), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge)
                actions.firstOrNull { it.kind == "back" }?.let { action ->
                    TextButton(onClick = { send(action, null) }) { Text("Done") }
                }
            }
            LazyColumn(Modifier.testTag("files.folder.appearance.options"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Icon", style = MaterialTheme.typography.titleMedium) }
                items(icons.chunked(3), key = { "icons:${it.first().optString("id")}" }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            val id = option.optString("id")
                            FilesAppearanceChoice(option.optString("label", id),
                                appearance.copy(icon = id), id == appearance.icon,
                                "files.folder.icon:$id", onClick = {
                                    iconAction?.let { send(it, JSONObject().put("icon", id)) }
                                }, modifier = Modifier.weight(1f))
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                item { Text("Color", style = MaterialTheme.typography.titleMedium) }
                items(colors.chunked(3), key = { "colors:${it.first().optString("id")}" }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            val id = option.optString("id")
                            FilesAppearanceChoice(option.optString("label", id),
                                appearance.copy(color = id), id == appearance.color,
                                "files.folder.color:$id", onClick = {
                                    colorAction?.let { send(it, JSONObject().put("color", id)) }
                                }, modifier = Modifier.weight(1f))
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                item {
                    actions.firstOrNull { it.kind == "resetFolderAppearance" }?.let { action ->
                        TextButton(onClick = { send(action, null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Use theme default")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesCleanSheet(page: JSONObject, browser: JSONObject?, items: List<JSONObject>,
    actions: List<PageAction>, send: (PageAction, JSONObject?) -> Unit,
    onOpenDocument: (String, String) -> Unit,
    documentLoader: suspend (String, String) -> ParcelFileDescriptor?, modifier: Modifier) {
    val scanning = page.optBoolean("cleanScanRunning") || page.optString("view") == "progress"
    val complete = page.optBoolean("complete", !scanning)
    val back = actions.firstOrNull { it.kind == "back" }
    BackHandler(enabled = back != null) { back?.let { send(it, null) } }
    Box(modifier.fillMaxSize().testTag("files.clean.sheet")) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(16.dp).alpha(.55f)) {
            Text(browser?.optString("title") ?: "Files", style = MaterialTheme.typography.titleLarge)
            browser?.optJSONArray("items").objects()?.take(8)?.forEach { item ->
                Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.optString("kind") == "folder") FilesFolderGlyph(
                        FilesFolderAppearance.fromJson(item.optJSONObject("folderAppearance")), Modifier.size(32.dp))
                    Text(item.optString("name"), modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .36f))
            .clickable(enabled = back != null) { back?.let { send(it, null) } })
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.82f).clickable { },
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (scanning) "Scanning folder" else "Clean folder",
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (scanning) actions.firstOrNull { it.kind == "cancel" }?.let { action ->
                        TextButton(onClick = { send(action, null) }, modifier = Modifier.testTag("files.clean.cancel")) {
                            Text("Cancel")
                        }
                    }
                    back?.let { action -> TextButton(onClick = { send(action, null) },
                        modifier = Modifier.testTag("files.clean.close")) { Text("Close") } }
                }
                page.optString("cleanScopeName").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val summary = page.optString("jobStatus").ifBlank { page.optString("summary") }
                if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (scanning) {
                    Spacer(Modifier.height(18.dp))
                    CircularProgressIndicator(Modifier.size(32.dp).testTag("files.clean.progress"))
                    Spacer(Modifier.height(6.dp))
                    Text("${page.optInt("findingCount", items.size)} findings so far", style = MaterialTheme.typography.bodySmall)
                }
                if (!scanning) {
                    val buttons = actions.filter { it.kind == "scan" || (complete &&
                        it.kind in setOf("toggle", "review", "settings")) }
                    FlowRow(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        buttons.forEach { action -> FilesActionTile(action, Modifier.widthIn(min = 132.dp)) {
                            send(action, null)
                        } }
                    }
                    page.optString("message").takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LazyColumn(Modifier.weight(1f).testTag("files.clean.findings"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(items, key = { it.optString("id") }) { item ->
                            Surface(Modifier.fillMaxWidth().testTag("files.clean.recommendation"),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Text(item.optString("name"), style = MaterialTheme.typography.titleSmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(item.optString("subtitle"), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!scanning && complete) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            item.optJSONArray("actions").objects().map(::PageAction).forEach { action ->
                                                TextButton(onClick = { send(action, null) },
                                                    modifier = Modifier.heightIn(min = 48.dp)) { Text(action.label) }
                                            }
                                        }
                                    }
                                }
                            }
                    }
                    actions.filter { it.kind == "loadMore" || it.kind == "previous" }.forEach { action ->
                        item(key = "page:${action.kind}") {
                            TextButton(onClick = { send(action, null) }, modifier = Modifier.fillMaxWidth()) {
                                Text(action.label)
                            }
                        }
                    }
                }
            }
        }
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
        if (kind == "folder" || kind == "location") {
            FilesFolderGlyph(FilesFolderAppearance.fromJson(item.optJSONObject("folderAppearance")),
                Modifier.size(if (compact) 40.dp else 48.dp))
            Spacer(Modifier.size(8.dp))
        } else if (item.optString("mimeType").startsWith("image/") && view != "review" && view != "cleanReview") {
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
            if (secondary.isNotEmpty()) {
                Box {
                    FilesMoreButton(expanded, "files.item.more") { expanded = true }
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

private data class FolderFlight(
    val id: String,
    val name: String,
    val subtitle: String,
    val appearance: FilesFolderAppearance,
    val bounds: Rect,
    val action: PageAction,
)

private class PageAction(value: JSONObject?) {
    val id: String = value?.optString("id") ?: ""
    val label: String = value?.optString("label") ?: ""
    val kind: String = value?.optString("kind") ?: ""
    val input: String? = value?.optString("input")?.takeIf { it.isNotBlank() }
    val initial: String = value?.optString("initial") ?: ""
}

@Composable
internal fun filesTrashColor(count: Long, emptyFolders: Long = 0): Color {
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    return when {
        count > 20 -> if (light) Color(0xFFAD2F39) else Color(0xFFFF737B)
        count > 0 || emptyFolders > 0 -> if (light) Color(0xFF1D64B0) else Color(0xFF6AA9FF)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
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
