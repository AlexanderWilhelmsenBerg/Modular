package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRouter
import io.github.alexanderwilhelmsenberg.modular.filesui.FilesPage
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.WorkspacePageContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IWorkspacePageCapability
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleConnectionStatus
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleDescriptorDiscovery
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
import io.github.alexanderwilhelmsenberg.modular.security.AndroidModuleIdentityResolver
import java.util.UUID
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive

/** Timeouts return to Home even if a remote Binder ignores cancellation. */
private object WorkspaceBinderCalls {
    private val slots = Semaphore(4)
    private val workers = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "workspace-page-binder").apply { isDaemon = true }
    }

    suspend fun <T> run(
        timeoutMillis: Long,
        onDiscard: (T) -> Unit = {},
        call: () -> T,
    ): T {
        check(slots.tryAcquire()) { "Module is busy; try again shortly" }
        val result = CompletableDeferred<Result<T>>()
        val lock = Any()
        var abandoned = false
        var completed: Result<T>? = null
        try {
            workers.execute {
                try {
                    val outcome = runCatching(call)
                    synchronized(lock) {
                        if (abandoned) outcome.onSuccess(onDiscard)
                        else {
                            completed = outcome
                            result.complete(outcome)
                        }
                    }
                }
                finally { slots.release() }
            }
        } catch (failure: RuntimeException) {
            slots.release()
            throw failure
        }
        return try {
            withTimeout(timeoutMillis) { result.await().getOrThrow() }
        } catch (failure: CancellationException) {
            synchronized(lock) {
                abandoned = true
                completed?.onSuccess(onDiscard)
                completed = null
            }
            throw failure
        }
    }
}

/** Core-owned page surface. Only a registered native renderer sees module data. */
@Composable
fun ModuleWorkspacePage(
    module: ModuleRecord?,
    router: CapabilityRouter,
    unavailableReason: String? = null,
    expectedSignerKey: String? = null,
    placementComponent: String? = module?.component?.flattenToString(),
    active: Boolean,
    onInteractionActiveChange: (Boolean) -> Unit,
    onReturnHome: () -> Unit,
    onOpenModuleSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val scrollStore = remember(context) { WorkspacePageScrollStore(context) }
    val snapshotStore = remember(context) { WorkspacePageSnapshotStore(context) }
    val moduleComponent = module?.component?.flattenToString()
    val snapshotWriteMutex = remember(placementComponent) { Mutex() }
    val currentActive by rememberUpdatedState(active)
    val currentSessionId by rememberUpdatedState(module?.sessionId)
    val currentComponent by rememberUpdatedState(moduleComponent)
    var resumeGeneration by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle, moduleComponent) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeGeneration++
            if (event == Lifecycle.Event.ON_STOP && moduleComponent != null) scrollStore.flush(moduleComponent)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (moduleComponent != null) scrollStore.flush(moduleComponent)
        }
    }
    DisposableEffect(active, moduleComponent) {
        onDispose { if (moduleComponent != null) scrollStore.flush(moduleComponent) }
    }
    var pageJson by remember(module?.component) { mutableStateOf<String?>(null) }
    var safeSnapshot by remember(placementComponent, expectedSignerKey, moduleComponent) {
        mutableStateOf<String?>(null)
    }
    var loadedSessionId by remember(module?.component) { mutableStateOf<String?>(null) }
    var message by remember(module?.component) { mutableStateOf<String?>(null) }
    var busy by remember(module?.component) { mutableStateOf(false) }
    var retryGeneration by remember(module?.component) { mutableIntStateOf(0) }
    var automaticRetries by remember(module?.component) { mutableIntStateOf(0) }
    var documentCommitUncertain by remember(module?.component) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) automaticRetries = 0 }
    LaunchedEffect(placementComponent, expectedSignerKey, unavailableReason) {
        if (placementComponent != null && (expectedSignerKey == null ||
            unavailableReason?.contains("signing identity changed", ignoreCase = true) == true)) {
            withContext(Dispatchers.IO) { snapshotStore.purge(placementComponent) }
            safeSnapshot = null
        }
    }
    LaunchedEffect(placementComponent, moduleComponent, expectedSignerKey) {
        if (placementComponent != null && placementComponent == moduleComponent && expectedSignerKey != null) {
            val restored = withContext(Dispatchers.IO) {
                snapshotStore.restore(placementComponent, expectedSignerKey)
            }
            if (pageJson == null) safeSnapshot = restored
        }
    }
    val routes by router.routes.collectAsState()
    val route = remember(module?.component, module?.sessionId, routes) {
        router.providersFor(StandardCapabilities.WorkspacePage).firstOrNull {
            it.component == module?.component && it.sessionId == module?.sessionId
        }
    }
    val connected = module?.status == ModuleConnectionStatus.CONNECTED && route != null
    val currentRoute by rememberUpdatedState(route)
    LaunchedEffect(pageJson, active, connected, loadedSessionId, module?.sessionId) {
        val content = pageJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val view = content?.optString("view")
        val interactive = active && connected && loadedSessionId == module?.sessionId && content != null && (
            (content.optJSONArray("selectedIds")?.length() ?: 0) > 0 ||
                content.optJSONObject("dialog") != null ||
                view in setOf("review", "markdown", "pdf", "clean", "cleanReview")
            )
        onInteractionActiveChange(interactive)
    }

    suspend fun capability(): IWorkspacePageCapability {
        val active = route ?: error("Module page provider is unavailable")
        val binder = WorkspaceBinderCalls.run(10_000) {
            runBlocking {
                router.acquireModuleBinder(active, instanceId = "workspace-page",
                    configurationJson = "{}").getOrThrow()
            }
        }
        return IWorkspacePageCapability.Stub.asInterface(binder)
            ?: error("Module page capability is unavailable")
    }

    fun validatedPage(raw: String): String {
        WorkspacePageContract.validateFilesPage(raw)
        return raw
    }

    suspend fun acceptPage(raw: String): String {
        val validated = validatedPage(raw)
        pageJson = validated
        if (moduleComponent != null && expectedSignerKey != null) {
            val display = WorkspacePageSnapshot.sanitize(validated)
            if (display == null) {
                if (WorkspacePageSnapshot.mustClear(validated)) {
                    safeSnapshot = null
                    snapshotWriteMutex.withLock {
                        withContext(Dispatchers.IO) { snapshotStore.purge(moduleComponent) }
                    }
                }
            } else {
                safeSnapshot = display
                snapshotWriteMutex.withLock {
                    withContext(Dispatchers.IO) { snapshotStore.save(moduleComponent, expectedSignerKey, validated) }
                }
            }
        }
        return validated
    }

    fun visibleAction(id: String): JSONObject? {
        val page = JSONObject(pageJson ?: "{}")
        val all = mutableListOf<JSONObject>()
        fun addActions(array: org.json.JSONArray?) {
            for (index in 0 until (array?.length() ?: 0)) {
                array?.optJSONObject(index)?.let(all::add)
            }
        }
        addActions(page.optJSONArray("actions"))
        addActions(page.optJSONObject("dialog")?.optJSONArray("actions"))
        val items = page.optJSONArray("items")
        for (index in 0 until (items?.length() ?: 0)) {
            addActions(items?.optJSONObject(index)?.optJSONArray("actions"))
        }
        return all.firstOrNull { it.optString("id") == id }
    }

    fun launchHostAction(hostAction: String, nonce: String) {
        val current = module ?: error("Module is unavailable")
        check(currentActive && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            "Return to Files to open this activity"
        }
        require(hostAction in setOf(WorkspacePageContract.HOST_PICK_FOLDER,
            WorkspacePageContract.HOST_FULL_ACCESS, WorkspacePageContract.HOST_OPEN_DOCUMENT,
            WorkspacePageContract.HOST_SHARE_DOCUMENT)) { "Unsupported module activity request" }
        require(nonce.length in 32..128 && nonce.matches(Regex("[A-Za-z0-9_-]+"))) {
            "Invalid module activity token"
        }
        val signer = AndroidModuleIdentityResolver(context)
            .resolve(current.component.packageName).getOrThrow().key
        check(expectedSignerKey != null && signer == expectedSignerKey) {
            "Module signing identity changed. Remove and add this screen again."
        }
        check(current.status == ModuleConnectionStatus.CONNECTED && route != null &&
            route in router.routes.value) {
            "Module page is no longer connected"
        }
        val declaration = current.descriptor?.managementActivity
            ?: error("Module has no setup screen")
        val target = ModuleDescriptorDiscovery.validateManagementActivity(context.packageManager,
            ServiceInfo().apply { packageName = current.component.packageName }, declaration)
        context.startActivity(Intent().setComponent(target)
            .putExtra(WorkspacePageContract.EXTRA_HOST_HANDOFF_NONCE, nonce))
    }

    fun submitAction(request: String) {
        if (busy) return
        if (!connected || loadedSessionId != module?.sessionId) {
            message = "Files is reconnecting. Try again shortly."
            return
        }
        val submittedSession = module?.sessionId
        val submittedRoute = route
        val hostAction: String
        try {
            val actionId = WorkspacePageContract.validateActionRequest(request)["actionId"]
                ?.jsonPrimitive?.content ?: error("Invalid action")
            val action = visibleAction(actionId)
            hostAction = action?.optString("hostAction").orEmpty()
        } catch (failure: Exception) {
            message = failure.message ?: "Action failed"
            return
        }
        scope.launch {
            busy = true
            try {
                WorkspacePageContract.validateActionRequest(request)
                val remote = capability()
                val raw = WorkspaceBinderCalls.run(20_000) { remote.performAction(request) }
                check(currentSessionId == submittedSession && currentRoute == submittedRoute &&
                    currentComponent == moduleComponent) { "Files session changed. Refreshing the page." }
                val validated = validatedPage(raw)
                if (hostAction.isNotEmpty()) {
                    val launch = JSONObject(validated).optJSONObject("hostLaunch")
                        ?: error("Module did not authorize the requested activity")
                    require(launch.optString("kind") == hostAction) {
                        "Module activity request changed"
                    }
                    launchHostAction(hostAction, launch.optString("nonce"))
                }
                acceptPage(validated)
                message = null
            } catch (failure: TimeoutCancellationException) {
                message = "Files did not confirm whether the action completed. Refresh and review the current files before trying again."
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                message = failure.message ?: "Action failed"
            } finally { busy = false }
        }
    }

    val livePage = pageJson?.takeIf { connected && loadedSessionId == module?.sessionId }
    BackHandler(enabled = active) {
        if (livePage == null) {
            onReturnHome()
            return@BackHandler
        }
        val page = JSONObject(livePage)
        val actions = page.optJSONArray("actions")
        val options = (0 until (actions?.length() ?: 0)).asSequence()
            .mapNotNull { actions?.optJSONObject(it) }
            .toList()
        val selectionActive = (page.optJSONArray("selectedIds")?.length() ?: 0) > 0
        val dialog = page.optJSONObject("dialog")
        val dialogActions = (0 until (dialog?.optJSONArray("actions")?.length() ?: 0)).asSequence()
            .mapNotNull { dialog?.optJSONArray("actions")?.optJSONObject(it) }.toList()
        val back = when {
            dialog != null -> dialogActions.firstOrNull { it.optString("kind") == "cancel" }
            selectionActive -> options.firstOrNull { it.optString("label") == "Clear selection" }
            page.optString("view") == "map" -> options.firstOrNull { it.optString("label") == "Up" }
            else -> null
        } ?: options.firstOrNull { it.optString("kind") == "back" }
        val id = back?.optString("id")?.takeIf { it.isNotBlank() }
        if (id == null) onReturnHome()
        else submitAction(JSONObject().put("version", 1).put("actionId", id)
            .put("operationToken", UUID.randomUUID().toString()).toString())
    }

    LaunchedEffect(module?.status, active, resumeGeneration, retryGeneration) {
        if (active && module?.status == ModuleConnectionStatus.FAILED && automaticRetries < 2) {
            delay(if (automaticRetries == 0) 700 else 1_500)
            automaticRetries++
            onRetry()
        }
    }

    LaunchedEffect(module?.sessionId, route, active, resumeGeneration, retryGeneration) {
        if (!active) return@LaunchedEffect
        val currentView = pageJson?.let { runCatching { JSONObject(it).optString("view") }.getOrNull() }
        if (connected && loadedSessionId == module?.sessionId && currentView in setOf("markdown", "pdf") &&
            !documentCommitUncertain) {
            return@LaunchedEffect
        }
        if (!connected) return@LaunchedEffect
        val loadingSession = module?.sessionId
        val loadingRoute = route
        busy = true
        try {
            repeat(3) { attempt ->
                try {
                    val remote = capability()
                    val request = JSONObject().put("version", 1).put("pageSize", 100)
                    moduleComponent?.let(scrollStore::restore)?.let { saved ->
                        request.put("restoreScroll", JSONObject().put("key", saved.key)
                            .put("anchor", saved.anchor).put("offset", saved.offset))
                    }
                    val raw = WorkspaceBinderCalls.run(10_000) { remote.getPage(request.toString()) }
                    check(currentSessionId == loadingSession && currentRoute == loadingRoute &&
                        currentComponent == moduleComponent) { "Files session changed while loading" }
                    acceptPage(raw)
                    loadedSessionId = module?.sessionId
                    documentCommitUncertain = false
                    automaticRetries = 0
                    message = null
                    return@LaunchedEffect
                } catch (failure: TimeoutCancellationException) {
                    message = "Files did not respond in time. Reconnecting…"
                    if (attempt < 2) delay(500L shl attempt)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    message = failure.message ?: "Files page could not load"
                    if (attempt < 2) delay(500L shl attempt)
                }
            }
            if (automaticRetries < 2) {
                automaticRetries++
                onRetry()
            }
        } finally { busy = false }
    }

    val pageInsetModifier = modifier.statusBarsPadding()
    if (livePage != null) {
        FilesPage(pageJson = livePage, modifier = pageInsetModifier,
            onScrollState = { key, anchor, offset ->
                moduleComponent?.let { scrollStore.record(it, key, anchor, offset) }
            },
            documentCancel = cancel@{ request ->
                if (documentCommitUncertain) return@cancel
                WorkspacePageContract.validateActionRequest(request)
                val submittedIdentity = Triple(module?.sessionId, route, moduleComponent)
                try {
                    val remote = capability()
                    if (Triple(currentSessionId, currentRoute, currentComponent) != submittedIdentity) {
                        message = "Files session changed before the document action. Refresh and review the current document."
                        retryGeneration++
                        return@cancel
                    }
                    val raw = WorkspaceBinderCalls.run(20_000) { remote.performAction(request) }
                    if (Triple(currentSessionId, currentRoute, currentComponent) != submittedIdentity) {
                        message = "Files session changed while completing the document action. Refresh and review the current document."
                        retryGeneration++
                        return@cancel
                    }
                    acceptPage(raw)
                } catch (failure: TimeoutCancellationException) {
                    message = "Files did not confirm whether the document action completed. Review the current document before trying again."
                    retryGeneration++
                }
            },
            documentCommit = commit@{ request ->
                if (documentCommitUncertain) {
                    message = "Review the current document before trying to save again. Your draft remains available."
                    return@commit false
                }
                WorkspacePageContract.validateActionRequest(request)
                documentCommitUncertain = false
                val submittedIdentity = Triple(module?.sessionId, route, moduleComponent)
                try {
                    val remote = capability()
                    if (Triple(currentSessionId, currentRoute, currentComponent) != submittedIdentity) {
                        documentCommitUncertain = true
                        message = "Files session changed before saving. Your draft remains available; refresh and review the document."
                        retryGeneration++
                        return@commit false
                    }
                    val raw = WorkspaceBinderCalls.run(20_000) { remote.performAction(request) }
                    if (Triple(currentSessionId, currentRoute, currentComponent) != submittedIdentity) {
                        documentCommitUncertain = true
                        message = "Files session changed while saving. Your draft remains available; refresh and review the document."
                        retryGeneration++
                        return@commit false
                    }
                    val validated = validatedPage(raw)
                    val saved = JSONObject(validated).optString("documentSaveStatus") == "saved"
                    acceptPage(validated)
                    saved
                } catch (failure: TimeoutCancellationException) {
                    documentCommitUncertain = true
                    message = "Files did not confirm whether the document was saved. Your draft remains available; review the document before trying again."
                    retryGeneration++
                    false
                }
            },
            documentLoader = { id, mode ->
                require(id.length in 1..WorkspacePageContract.MAX_DOCUMENT_ID_LENGTH &&
                    WorkspacePageContract.validDocumentMode(mode)) { "Invalid document request" }
                check(!documentCommitUncertain || mode == "r") {
                    "Review the current document before trying to save again"
                }
                val submittedIdentity = Triple(module?.sessionId, route, moduleComponent)
                val remote = capability()
                check(Triple(currentSessionId, currentRoute, currentComponent) == submittedIdentity) {
                    "Files session changed before opening the document"
                }
                val descriptor = WorkspaceBinderCalls.run(10_000, onDiscard = { it?.close() }) {
                    remote.openDocument(id, mode)
                }
                if (Triple(currentSessionId, currentRoute, currentComponent) != submittedIdentity) {
                    runCatching { descriptor?.close() }
                    error("Files session changed while opening the document")
                }
                descriptor
            },
            onAction = ::submitAction, onOpenDocument = { id, mode ->
                try {
                    require(mode == "r") { "Invalid document mode" }
                    val page = JSONObject(pageJson ?: "{}")
                    val items = page.optJSONArray("items")
                    val item = (0 until (items?.length() ?: 0)).asSequence()
                        .mapNotNull { items?.optJSONObject(it) }
                        .firstOrNull { it.optString("id") == id }
                    val actions = item?.optJSONArray("actions")
                    val open = (0 until (actions?.length() ?: 0)).asSequence()
                        .mapNotNull { actions?.optJSONObject(it) }
                        .firstOrNull { it.optString("hostAction") == WorkspacePageContract.HOST_OPEN_DOCUMENT }
                        ?: error("Open with is unavailable for this item")
                    submitAction(JSONObject().put("version", 1).put("actionId", open.optString("id"))
                        .put("operationToken", UUID.randomUUID().toString()).toString())
                } catch (failure: Exception) {
                    message = failure.message ?: "Could not open document"
                }
            })
        if (message != null) AlertDialog(onDismissRequest = { message = null },
            title = { Text("Files action failed") }, text = { Text(requireNotNull(message)) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("Close") } })
    } else if (safeSnapshot != null && module != null &&
        module.status in setOf(ModuleConnectionStatus.CONNECTING, ModuleConnectionStatus.DISCOVERED,
            ModuleConnectionStatus.CONNECTED, ModuleConnectionStatus.FAILED)) {
        Box(pageInsetModifier.fillMaxSize()) {
            FilesPage(pageJson = requireNotNull(safeSnapshot), onAction = {}, onOpenDocument = { _, _ -> },
                documentLoader = { _, _ -> null }, modifier = Modifier.fillMaxSize())
            androidx.compose.material3.Surface(
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(12.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(message ?: "Reconnecting to Files…", style = MaterialTheme.typography.bodyMedium)
                    if (message != null && !busy && automaticRetries >= 2) {
                        Button(onClick = {
                            retryGeneration++
                            if (module.status != ModuleConnectionStatus.CONNECTED || route == null) onRetry()
                        }) { Text("Retry") }
                    }
                }
            }
        }
    } else {
        Column(pageInsetModifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(module?.label ?: "Module page unavailable", style = MaterialTheme.typography.titleLarge)
            Text(message ?: unavailableReason ?: when (module?.status) {
                null -> "The module is no longer installed. Its Home placement is saved."
                ModuleConnectionStatus.DISABLED -> "Enable this module to open its page."
                ModuleConnectionStatus.CONNECTING, ModuleConnectionStatus.DISCOVERED -> "Connecting to the module…"
                else -> "The module page is unavailable. Its Home placement is saved."
            }, modifier = Modifier.padding(vertical = 12.dp))
            if (busy) CircularProgressIndicator()
            if (module != null) {
                Button(onClick = {
                    retryGeneration++
                    if (module.status != ModuleConnectionStatus.CONNECTED || route == null) onRetry()
                }, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
                OutlinedButton(onClick = onOpenModuleSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Module settings")
                }
            }
        }
    }
}
