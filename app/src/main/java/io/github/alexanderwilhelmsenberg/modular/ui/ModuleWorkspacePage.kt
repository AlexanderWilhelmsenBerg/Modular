package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRouter
import io.github.alexanderwilhelmsenberg.modular.filesui.FilesPage
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.WorkspacePageContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IWorkspacePageCapability
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleConnectionStatus
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
import java.io.File
import java.util.UUID
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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
    var resumeGeneration by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeGeneration++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var pageJson by remember(module?.component) { mutableStateOf<String?>(null) }
    var loadedSessionId by remember(module?.component) { mutableStateOf<String?>(null) }
    var message by remember(module?.component) { mutableStateOf<String?>(null) }
    var busy by remember(module?.component) { mutableStateOf(false) }
    val routes by router.routes.collectAsState()
    val route = remember(module?.component, module?.sessionId, routes) {
        router.providersFor(StandardCapabilities.WorkspacePage).firstOrNull {
            it.component == module?.component && it.sessionId == module?.sessionId
        }
    }
    LaunchedEffect(pageJson, active) {
        val content = pageJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val view = content?.optString("view")
        val interactive = active && content != null && (
            (content.optJSONArray("selectedIds")?.length() ?: 0) > 0 ||
                content.optJSONObject("dialog") != null ||
                view in setOf("review", "markdown", "pdf")
            )
        onInteractionActiveChange(interactive)
    }

    suspend fun capability(): IWorkspacePageCapability {
        val active = route ?: error("Module page provider is unavailable")
        val binder = router.acquireModuleBinder(
            active, instanceId = "workspace-page", configurationJson = "{}",
        ).getOrThrow()
        return IWorkspacePageCapability.Stub.asInterface(binder)
            ?: error("Module page capability is unavailable")
    }

    fun validatedPage(raw: String): String {
        WorkspacePageContract.validateFilesPage(raw)
        return raw
    }

    fun submitAction(request: String) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                WorkspacePageContract.validateActionRequest(request)
                val remote = capability()
                val raw = WorkspaceBinderCalls.run(20_000) { remote.performAction(request) }
                pageJson = validatedPage(raw)
                message = null
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                message = failure.message ?: "Action failed"
            } finally { busy = false }
        }
    }

    BackHandler(enabled = active) {
        val actions = JSONObject(pageJson ?: "{}").optJSONArray("actions")
        val back = (0 until (actions?.length() ?: 0)).asSequence()
            .mapNotNull { actions?.optJSONObject(it) }
            .firstOrNull { it.optString("kind") == "back" }
        val id = back?.optString("id")?.takeIf { it.isNotBlank() }
        if (id == null) onReturnHome()
        else submitAction(JSONObject().put("version", 1).put("actionId", id)
            .put("operationToken", UUID.randomUUID().toString()).toString())
    }

    LaunchedEffect(module?.sessionId, route, active, resumeGeneration) {
        if (!active) return@LaunchedEffect
        val currentView = pageJson?.let { runCatching { JSONObject(it).optString("view") }.getOrNull() }
        if (loadedSessionId == module?.sessionId && currentView in setOf("markdown", "pdf")) {
            return@LaunchedEffect
        }
        pageJson = null
        if (module?.status != ModuleConnectionStatus.CONNECTED || route == null) return@LaunchedEffect
        busy = true
        try {
            val remote = capability()
            val raw = WorkspaceBinderCalls.run(10_000) {
                remote.getPage("{\"version\":1,\"pageSize\":100}")
            }
            pageJson = validatedPage(raw)
            loadedSessionId = module.sessionId
            message = null
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            message = failure.message ?: "Files page could not load"
        } finally { busy = false }
    }

    if (pageJson != null && module?.status == ModuleConnectionStatus.CONNECTED && route != null) {
        FilesPage(pageJson = requireNotNull(pageJson), modifier = modifier,
            documentCancel = { request ->
                WorkspacePageContract.validateActionRequest(request)
                val remote = capability()
                val raw = WorkspaceBinderCalls.run(20_000) { remote.performAction(request) }
                pageJson = validatedPage(raw)
            },
            documentCommit = { request ->
                WorkspacePageContract.validateActionRequest(request)
                val remote = capability()
                val raw = WorkspaceBinderCalls.run(20_000) { remote.performAction(request) }
                val validated = validatedPage(raw)
                val saved = JSONObject(validated).optString("documentSaveStatus") == "saved"
                pageJson = validated
                saved
            },
            documentLoader = { id, mode ->
                require(id.length in 1..WorkspacePageContract.MAX_DOCUMENT_ID_LENGTH &&
                    WorkspacePageContract.validDocumentMode(mode)) { "Invalid document request" }
                val remote = capability()
                WorkspaceBinderCalls.run(10_000, onDiscard = { it?.close() }) {
                    remote.openDocument(id, mode)
                }
            },
            onAction = ::submitAction, onOpenDocument = { id, mode ->
                scope.launch {
                    try {
                        require(id.length in 1..WorkspacePageContract.MAX_DOCUMENT_ID_LENGTH && mode == "r") {
                            "Invalid document request"
                        }
                        val items = JSONObject(pageJson ?: "{}").optJSONArray("items")
                        val item = (0 until (items?.length() ?: 0)).asSequence()
                            .mapNotNull { items?.optJSONObject(it) }
                            .firstOrNull { it.optString("id") == id }
                        val extension = item?.optString("name")?.substringAfterLast('.', "")
                            ?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }?.lowercase()
                        val mime = item?.optString("mimeType")?.takeIf { it.contains('/') }
                            ?: extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                            ?: "application/octet-stream"
                        val remote = capability()
                        val file = WorkspaceBinderCalls.run(30_000, onDiscard = { it.delete() }) {
                            val descriptor: ParcelFileDescriptor = remote.openDocument(id, "r")
                                ?: error("Document is unavailable")
                            context.cacheDir.listFiles()?.filter { candidate ->
                                candidate.name.startsWith("workspace-document-") &&
                                    System.currentTimeMillis() - candidate.lastModified() > 24L * 60 * 60 * 1000
                            }?.forEach { it.delete() }
                            val destination = File(context.cacheDir,
                                "workspace-document-${UUID.randomUUID()}${extension?.let { ".$it" } ?: ""}")
                            try {
                                descriptor.use { input ->
                                    ParcelFileDescriptor.AutoCloseInputStream(input).use { source ->
                                        destination.outputStream().use { output ->
                                            val buffer = ByteArray(32_768)
                                            var copied = 0L
                                            while (true) {
                                                val count = source.read(buffer)
                                                if (count < 0) break
                                                copied += count
                                                require(copied <= 64L * 1024 * 1024) { "Document is too large to open externally" }
                                                output.write(buffer, 0, count)
                                            }
                                        }
                                    }
                                }
                            } catch (failure: Exception) {
                                destination.delete()
                                throw failure
                            }
                            destination
                        }
                        val uri = FileProvider.getUriForFile(context,
                            "${context.packageName}.workspacefiles", file)
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: ActivityNotFoundException) {
                        Toast.makeText(context, "No app can open this document", Toast.LENGTH_LONG).show()
                    } catch (failure: Exception) {
                        Toast.makeText(context, failure.message ?: "Could not open document", Toast.LENGTH_LONG).show()
                    }
                }
            })
        if (message != null) AlertDialog(onDismissRequest = { message = null },
            title = { Text("Files action failed") }, text = { Text(requireNotNull(message)) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("Close") } })
    } else {
        Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(module?.label ?: "Module page unavailable", style = MaterialTheme.typography.titleLarge)
            Text(message ?: when (module?.status) {
                null -> "The module is no longer installed. Its Home placement is saved."
                ModuleConnectionStatus.DISABLED -> "Enable this module to open its page."
                ModuleConnectionStatus.CONNECTING, ModuleConnectionStatus.DISCOVERED -> "Connecting to the module…"
                else -> "The module page is unavailable. Its Home placement is saved."
            }, modifier = Modifier.padding(vertical = 12.dp))
            if (busy) CircularProgressIndicator()
            if (module != null) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
                OutlinedButton(onClick = onOpenModuleSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Module settings")
                }
            }
        }
    }
}
