package io.github.alexanderwilhelmsenberg.modular.addon.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.alexanderwilhelmsenberg.modular.filesui.FilesPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** The same module-owned document/action pipeline is used by the Activity and remote surface. */
@Composable
internal fun FilesNativeContent(
    controller: FilesPageController,
    refreshToken: Int,
    onHostLaunch: (String) -> Unit,
    onExternalDocument: suspend (String) -> Unit,
    onBackState: (Boolean, Boolean, (() -> Boolean)?) -> Unit = { _, _, _ -> },
    onRequestApps: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val ioMutex = remember { Mutex() }
    var page by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = remember { ScrollSaveState() }

    fun saveScroll(position: Triple<String, Int, Int>, debounce: Boolean) {
        scroll.job?.cancel()
        scroll.job = scope.launch {
            if (debounce) delay(250)
            withContext(Dispatchers.IO) {
                ioMutex.withLock { controller.recordScrollPosition(position.first, position.second, position.third) }
            }
        }
    }

    fun load() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ioMutex.withLock { runCatching { controller.getPage(PAGE_REQUEST) } }
            }
            result.onSuccess { page = it; error = null }
                .onFailure { error = it.message ?: "Files could not be loaded" }
        }
    }

    fun performAction(request: String) {
        scroll.job?.cancel()
        val position = scroll.pending
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ioMutex.withLock {
                    runCatching {
                        position?.let { controller.recordScrollPosition(it.first, it.second, it.third) }
                        controller.performAction(request)
                    }
                }
            }
            result.onSuccess { response ->
                page = response
                error = null
                JSONObject(response).optJSONObject("hostLaunch")?.optString("nonce")
                    ?.takeIf { it.isNotBlank() }?.let(onHostLaunch)
            }.onFailure { error = it.message ?: "Files action failed" }
        }
    }

    LaunchedEffect(refreshToken) { load() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) load()
            if (event == Lifecycle.Event.ON_STOP) scroll.pending?.let { saveScroll(it, debounce = false) }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            scroll.job?.cancel()
            scroll.pending?.let { position ->
                CoroutineScope(Dispatchers.IO).launch {
                    controller.recordScrollPosition(position.first, position.second, position.third)
                }
            }
            onBackState(false, true, null)
        }
    }

    val uiState = remember(page) { filesSurfaceUiState(page) }
    SideEffect {
        onBackState(uiState.backActionId != null, uiState.exclusiveGestures, uiState.backActionId?.let { id ->
            { performAction(actionRequest(id)); true }
        })
    }

    val appsThresholdPx = with(LocalDensity.current) { 64.dp.toPx() }
    val appsGesture = remember(uiState.allowAppsSwipe, onRequestApps, appsThresholdPx) {
        object : NestedScrollConnection {
            private var upwardPx = 0f
            private var requested = false

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || !uiState.allowAppsSwipe || onRequestApps == null)
                    return Offset.Zero
                if (available.y >= 0f) {
                    upwardPx = 0f
                    return Offset.Zero
                }
                if (!requested) {
                    upwardPx += -available.y
                    if (upwardPx >= appsThresholdPx) {
                        requested = true
                        onRequestApps?.invoke()
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                upwardPx = 0f
                requested = false
                return Velocity.Zero
            }
        }
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .then(if (onRequestApps != null && uiState.allowAppsSwipe) Modifier.nestedScroll(appsGesture) else Modifier)) {
        val currentPage = page
        if (currentPage == null) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error ?: "Loading Files…")
                Button(onClick = ::load) { Text("Retry") }
            }
        } else {
            FilesPage(currentPage,
                onScrollState = { key, anchor, offset ->
                    val position = Triple(key, anchor, offset)
                    scroll.pending = position
                    saveScroll(position, debounce = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                },
                documentLoader = { id, mode -> withContext(Dispatchers.IO) {
                    ioMutex.withLock { controller.openDocument(id, mode) }
                } },
                documentCommit = { request ->
                    val result = withContext(Dispatchers.IO) {
                        ioMutex.withLock { runCatching { controller.performAction(request) } }
                    }
                    result.onSuccess { page = it; error = null }
                        .onFailure { error = it.message ?: "Document save failed" }
                    result.getOrNull()?.let { JSONObject(it).optString("documentSaveStatus") == "saved" } ?: false
                },
                documentCancel = { request ->
                    val result = withContext(Dispatchers.IO) {
                        ioMutex.withLock { runCatching { controller.performAction(request) } }
                    }
                    result.onSuccess { page = it; error = null }
                        .onFailure { error = it.message ?: "Document cancel failed" }
                },
                onAction = ::performAction,
                onOpenDocument = { id, mode ->
                    scope.launch {
                        runCatching {
                            if (mode == "internalEditor") {
                                val nonce = withContext(Dispatchers.IO) { controller.createEditorHandoff(id) }
                                onHostLaunch(nonce)
                            } else onExternalDocument(id)
                        }
                            .onFailure { error = it.message ?: "Document could not be opened" }
                    }
                })
            error?.let { message ->
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(8.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                    Button(onClick = ::load) { Text("Refresh Files") }
                }
            }
        }
    }
}

private const val PAGE_REQUEST = "{\"version\":1,\"pageSize\":100}"

internal data class FilesSurfaceUiState(
    val backActionId: String?,
    val exclusiveGestures: Boolean,
    val allowAppsSwipe: Boolean,
)

/** Only Files interprets its page state; Core receives generic surface flags. */
internal fun filesSurfaceUiState(pageJson: String?): FilesSurfaceUiState {
    val page = pageJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?: return FilesSurfaceUiState(null, exclusiveGestures = true, allowAppsSwipe = false)
    if (page.optString("view").isBlank())
        return FilesSurfaceUiState(null, exclusiveGestures = true, allowAppsSwipe = false)
    val actions = page.optJSONArray("actions")
    val backActionId = (0 until (actions?.length() ?: 0)).mapNotNull { actions?.optJSONObject(it) }
        .firstOrNull { it.optString("kind") == "back" }?.optString("id")?.takeIf { it.isNotBlank() }
    val view = page.optString("view")
    val protectedView = view in setOf("review", "markdown", "pdf", "clean", "cleanReview", "progress", "keeper")
    val exclusive = protectedView || (page.optJSONArray("selectedIds")?.length() ?: 0) > 0 ||
        page.optJSONObject("dialog") != null
    return FilesSurfaceUiState(backActionId, exclusive,
        allowAppsSwipe = !exclusive && view in setOf("browser", "overview", "search"))
}

private class ScrollSaveState(
    var pending: Triple<String, Int, Int>? = null,
    var job: Job? = null,
)

private fun actionRequest(id: String): String = JSONObject().put("version", 1).put("actionId", id)
    .put("operationToken", UUID.randomUUID().toString()).toString()
