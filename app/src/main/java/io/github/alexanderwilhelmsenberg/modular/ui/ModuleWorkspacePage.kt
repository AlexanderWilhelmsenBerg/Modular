package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.graphics.PixelFormat
import android.util.Log
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRouter
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IWorkspaceSurfaceCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IWorkspaceSurfaceHost
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IWorkspaceSurfaceSession
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleConnectionStatus
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleDescriptorDiscovery
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
import io.github.alexanderwilhelmsenberg.modular.security.AndroidModuleIdentityResolver
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.json.JSONObject

/** Binder calls may block past their deadline. Four workers cap retained threads and requests. */
private object SurfaceBinderCalls {
    private val slots = Semaphore(4)
    private val workers = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "workspace-surface-binder").apply { isDaemon = true }
    }

    suspend fun <T> run(timeoutMillis: Long, onDiscard: (T) -> Unit = {}, call: () -> T): T {
        check(slots.tryAcquire()) { "The add-on is busy. Try again shortly." }
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
                } finally { slots.release() }
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

private object SurfaceCleanup {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/** Compose tests may replace Dispatchers.Main with an unconfined dispatcher. */
private object SurfaceUiThread {
    val dispatcher = Handler(Looper.getMainLooper()).asCoroutineDispatcher("workspace-surface-ui")
}

private const val SURFACE_LOG = "ModuleWorkspaceSurface"

private fun sameSurfaceRoute(left: CapabilityRoute?, right: CapabilityRoute?): Boolean =
    left != null && right != null && left.component == right.component &&
        left.sessionId == right.sessionId && left.capability == right.capability

private data class SurfaceEffectSnapshot(
    val eligible: Boolean,
    val active: Boolean,
    val resumed: Boolean,
    val route: CapabilityRoute?,
    val moduleStatus: ModuleConnectionStatus?,
    val view: SurfaceView?,
    val generation: Int,
    val retry: Int,
    val signer: String?,
    val placement: String?,
)

private class SurfaceLease(
    val route: CapabilityRoute,
    val id: String,
    val session: IWorkspaceSurfaceSession,
    val hostToken: IBinder,
    val view: SurfaceView,
    val surfacePackage: SurfaceControlViewHost.SurfacePackage,
    val death: IBinder.DeathRecipient,
) {
    var state by mutableStateOf(SurfaceState())
    var closed = false
    fun detach() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            SurfaceCleanup.scope.launch(SurfaceUiThread.dispatcher) { detach() }
            return
        }
        if (closed) return
        closed = true
        view.visibility = android.view.View.INVISIBLE
        // Older releases replace the child on the next setChildSurfacePackage call.
        // Release and close remove its resources while the SurfaceView stays hidden.
        if (Build.VERSION.SDK_INT >= 36) runCatching { view.clearChildSurfacePackage() }
        // A package update kills the provider before its death callback reaches us.
        // Notify it when possible, then continue releasing local resources.
        if (Build.VERSION.SDK_INT >= 33) runCatching { surfacePackage.notifyDetachedFromWindow() }
        runCatching { session.asBinder().unlinkToDeath(death, 0) }
        if (Build.VERSION.SDK_INT >= 30) runCatching { surfacePackage.release() }
        SurfaceCleanup.scope.launch { runCatching { SurfaceBinderCalls.run(3_000) { session.close() } } }
    }
}

internal data class SurfaceState(val canGoBack: Boolean = false, val exclusiveGestures: Boolean = true)

internal fun parseSurfaceState(raw: String): SurfaceState {
    require(raw.toByteArray(Charsets.UTF_8).size <= 8 * 1024) { "Surface state is too large" }
    val json = JSONObject(raw)
    require(json.getInt("version") == 1) { "Unsupported surface state version" }
    fun flag(name: String, default: Boolean): Boolean {
        if (!json.has(name)) return default
        require(json.get(name) is Boolean) { "Invalid $name" }
        return json.getBoolean(name)
    }
    return SurfaceState(flag("canGoBack", false), flag("exclusiveGestures", true))
}

private fun validNonce(nonce: String): Boolean = nonce.length in 32..128 &&
    nonce.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

/** A provider's view remains in its own process. Core only owns the placement and SurfaceView. */
@Composable
fun ModuleWorkspacePage(
    module: ModuleRecord?,
    router: CapabilityRouter,
    unavailableReason: String? = null,
    expectedSignerKey: String? = null,
    placementComponent: String? = module?.component?.flattenToString(),
    active: Boolean,
    onInteractionActiveChange: (Boolean) -> Unit,
    onNavigate: (String) -> Unit,
    onOpenModuleSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewportModifier = modifier.fillMaxSize().statusBarsPadding()
        .navigationBarsPadding().imePadding().clipToBounds()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val routes by router.routes.collectAsState()
    val route = routes.firstOrNull { it.capability == StandardCapabilities.WorkspaceSurface &&
        it.component == module?.component && it.sessionId == module?.sessionId }
    val currentRoute by rememberUpdatedState(route)
    val currentActive by rememberUpdatedState(active)
    val currentModule by rememberUpdatedState(module)
    val currentNavigate by rememberUpdatedState(onNavigate)
    val currentInteraction by rememberUpdatedState(onInteractionActiveChange)
    val colorScheme = MaterialTheme.colorScheme
    val colors = remember(colorScheme) {
        JSONObject().put("background", colorScheme.background.toArgb())
            .put("surface", colorScheme.surface.toArgb())
            .put("onSurface", colorScheme.onSurface.toArgb())
            .put("primary", colorScheme.primary.toArgb())
            .put("onPrimary", colorScheme.onPrimary.toArgb())
            .put("primaryContainer", colorScheme.primaryContainer.toArgb())
            .put("onPrimaryContainer", colorScheme.onPrimaryContainer.toArgb())
    }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var width by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    var surfaceGeneration by remember { mutableIntStateOf(0) }
    var retryGeneration by remember { mutableIntStateOf(0) }
    var lease by remember { mutableStateOf<SurfaceLease?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var automaticRetries by remember { mutableIntStateOf(0) }
    val previousEffect = remember { AtomicReference<SurfaceEffectSnapshot?>(null) }
    val currentLease by rememberUpdatedState(lease)
    val surfaceMinSdk = module?.descriptor?.workspaceSurfaceMinSdk ?: 30
    val canShow = Build.VERSION.SDK_INT >= surfaceMinSdk && active && resumed &&
        module?.status == ModuleConnectionStatus.CONNECTED && route != null &&
        module.component.flattenToString() == placementComponent && expectedSignerKey != null

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            lease?.detach()
            currentInteraction(false)
        }
    }
    LaunchedEffect(canShow, lease?.state, lease) {
        onInteractionActiveChange(canShow && lease != null && lease?.state?.exclusiveGestures == true)
    }

    fun verifiedSession(candidate: SurfaceLease): Boolean = !candidate.closed &&
        candidate === currentLease && currentActive && resumed &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        sameSurfaceRoute(currentRoute, candidate.route) &&
        currentModule?.sessionId == candidate.route.sessionId

    fun launchDeclaredActivity(candidate: SurfaceLease, nonce: String) {
        if (!verifiedSession(candidate) || !validNonce(nonce)) return
        val record = currentModule ?: return
        val declaration = record.descriptor?.managementActivity ?: return
        try {
            val signer = AndroidModuleIdentityResolver(context)
                .resolve(record.component.packageName).getOrThrow().key
            check(signer == expectedSignerKey && candidate.route in router.routes.value)
            val component = ModuleDescriptorDiscovery.validateManagementActivity(
                context.packageManager, ServiceInfo().apply { packageName = record.component.packageName }, declaration)
            context.startActivity(Intent().setComponent(component)
                .putExtra(ModuleContract.EXTRA_HOST_HANDOFF_NONCE, nonce))
        } catch (failure: Exception) {
            message = "Could not open the add-on activity. Refresh the module and try again."
        }
    }

    val routeIdentity = route?.let { Triple(it.component, it.sessionId, it.capability) }
    LaunchedEffect(canShow, routeIdentity, surfaceView, surfaceGeneration,
        retryGeneration, expectedSignerKey, placementComponent) {
        withContext(SurfaceUiThread.dispatcher) {
        val now = SurfaceEffectSnapshot(canShow, active, resumed, route, module?.status,
            surfaceView, surfaceGeneration, retryGeneration, expectedSignerKey, placementComponent)
        val prior = previousEffect.getAndSet(now)
        val changes = if (prior == null) "initial" else buildList {
            if (prior.eligible != now.eligible) add("eligible")
            if (prior.active != now.active) add("active")
            if (prior.resumed != now.resumed) add("resumed")
            if (!sameSurfaceRoute(prior.route, now.route)) add("routeIdentity")
            else if (prior.route != now.route) add("routeMetadata")
            if (prior.moduleStatus != now.moduleStatus) add("status")
            if (prior.view !== now.view) add("view")
            if (prior.generation != now.generation) add("surfaceGeneration")
            if (prior.retry != now.retry) add("retry")
            if (prior.signer != now.signer) add("signer")
            if (prior.placement != now.placement) add("placement")
        }.joinToString(",").ifBlank { "no-key-change" }
        Log.d(SURFACE_LOG, "session effect: changes=$changes active=$active resumed=$resumed generation=$surfaceGeneration size=${width}x$height")
        lease?.detach()
        lease = null
        if (Build.VERSION.SDK_INT < 30) return@withContext
        if (!canShow) return@withContext
        val view = surfaceView ?: return@withContext
        // A retained pager view was hidden when its previous session detached.
        // Restore it before waiting for a host token and size: SurfaceView only
        // recreates its holder surface and reports dimensions while visible.
        view.visibility = android.view.View.VISIBLE
        val token = view.hostToken ?: run {
            Log.d(SURFACE_LOG, "host token unavailable")
            return@withContext
        }
        val sessionWidth = width
        val sessionHeight = height
        if (sessionWidth !in 1..8192 || sessionHeight !in 1..8192 ||
            sessionWidth.toLong() * sessionHeight > 16_777_216) return@withContext
        val selectedRoute = router.routes.value.firstOrNull { sameSurfaceRoute(it, route) }
            ?: return@withContext
        val selectedModule = module ?: return@withContext
        val id = UUID.randomUUID().toString()
        val expectedUid = try { context.packageManager.getApplicationInfo(selectedModule.component.packageName, 0).uid }
            catch (_: Exception) { message = "The add-on is unavailable"; return@withContext }
        val pendingState = AtomicReference<SurfaceState?>(null)
        val host = object : IWorkspaceSurfaceHost.Stub() {
            private fun accepted(sessionId: String): Boolean = Binder.getCallingUid() == expectedUid && sessionId == id
            override fun onStateChanged(sessionId: String, stateJson: String) {
                if (!accepted(sessionId)) return
                val parsed = runCatching { parseSurfaceState(stateJson) }.getOrNull() ?: return
                scope.launch(SurfaceUiThread.dispatcher) {
                    val live = currentLease?.takeIf { it.id == id && verifiedSession(it) }
                    if (live == null) pendingState.set(parsed) else live.state = parsed
                }
            }
            override fun requestNavigation(sessionId: String, destination: String) {
                if (!accepted(sessionId) || destination !in setOf("home", "apps", "previous", "next", "settings")) return
                scope.launch(SurfaceUiThread.dispatcher) { currentLease?.takeIf { it.id == id && verifiedSession(it) }?.let {
                    currentNavigate(destination)
                } }
            }
            override fun requestActivity(sessionId: String, nonce: String) {
                if (!accepted(sessionId) || !validNonce(nonce)) return
                scope.launch(SurfaceUiThread.dispatcher) { currentLease?.takeIf { it.id == id }?.let { launchDeclaredActivity(it, nonce) } }
            }
        }
        val sessionHostJson = JSONObject().put("version", 1).put("sessionId", id)
            .put("widthPx", sessionWidth).put("heightPx", sessionHeight)
            .put("displayId", (view.display?.displayId ?: 0).coerceAtLeast(0))
            .put("densityDpi", configuration.densityDpi.coerceIn(72, 960))
            .put("fontScale", configuration.fontScale.coerceIn(0.5f, 3f).toDouble())
            .put("visible", true)
            .put("theme", when {
                colorScheme.background.toArgb() == android.graphics.Color.BLACK -> "amoled"
                colorScheme.background.luminance() < 0.5f -> "dark"
                else -> "light"
            })
            .put("colors", colors)
            .put("localeTags", configuration.locales.toLanguageTags().take(256))
            .toString()
        loading = true
        message = null
        Log.d(SURFACE_LOG, "opening surface session")
        var pendingSession: IWorkspaceSurfaceSession? = null
        var pendingPackage: SurfaceControlViewHost.SurfacePackage? = null
        try {
            val binder = SurfaceBinderCalls.run(10_000) {
                runBlocking { router.acquireModuleBinder(selectedRoute, "workspace-surface", "{}").getOrThrow() }
            }
            Log.d(SURFACE_LOG, "capability acquired")
            val remote = IWorkspaceSurfaceCapability.Stub.asInterface(binder)
                ?: error("The add-on surface is unavailable")
            val session = SurfaceBinderCalls.run(10_000, onDiscard = { it?.let { abandoned ->
                SurfaceCleanup.scope.launch { runCatching { SurfaceBinderCalls.run(3_000) { abandoned.close() } } }
            } }) { remote.createSession(token, sessionHostJson, host) }
                ?: error("The add-on did not create a surface")
            Log.d(SURFACE_LOG, "session created")
            pendingSession = session
            val pkg = SurfaceBinderCalls.run(10_000, onDiscard = {
                if (Build.VERSION.SDK_INT >= 30) it?.release()
            }) {
                session.surfacePackage
            } ?: error("The add-on did not provide a surface")
            Log.d(SURFACE_LOG, "surface package received")
            pendingPackage = pkg
            if (!active || !resumed || !sameSurfaceRoute(currentRoute, selectedRoute) ||
                currentModule?.sessionId != selectedRoute.sessionId ||
                surfaceView !== view || view.hostToken != token) {
                Log.d(SURFACE_LOG, "session changed before attach")
                return@withContext
            }
            val death = IBinder.DeathRecipient {
                scope.launch(SurfaceUiThread.dispatcher) { currentLease?.takeIf { it.id == id }?.let {
                    it.detach(); lease = null; message = "The add-on disconnected. Retry to reconnect."
                } }
            }
            session.asBinder().linkToDeath(death, 0)
            val created = SurfaceLease(selectedRoute, id, session, token, view, pkg, death)
            view.setChildSurfacePackage(pkg)
            view.visibility = android.view.View.VISIBLE
            view.requestFocus()
            lease = created
            Log.d(SURFACE_LOG, "surface package attached")
            pendingState.getAndSet(null)?.let { created.state = it }
            pendingSession = null
            pendingPackage = null
            automaticRetries = 0
        } catch (_: TimeoutCancellationException) {
            Log.w(SURFACE_LOG, "surface session timed out")
            message = "The add-on did not respond in time. Retry to reconnect."
        } catch (cancelled: CancellationException) {
            Log.d(SURFACE_LOG, "surface session cancelled")
            throw cancelled
        } catch (failure: Exception) {
            Log.w(SURFACE_LOG, "surface session failed: ${failure.javaClass.simpleName}")
            message = failure.message ?: "Could not open the add-on surface"
        } finally {
            if (Build.VERSION.SDK_INT >= 30) pendingPackage?.release()
            pendingSession?.let { abandoned ->
                SurfaceCleanup.scope.launch { runCatching { SurfaceBinderCalls.run(3_000) { abandoned.close() } } }
            }
            loading = false
        }
        }
    }
    LaunchedEffect(width, height, colors, configuration, lease) {
        withContext(SurfaceUiThread.dispatcher) {
        val current = lease ?: return@withContext
        if (width !in 1..8192 || height !in 1..8192 || width.toLong() * height > 16_777_216) return@withContext
        val update = JSONObject().put("version", 1).put("sessionId", current.id)
            .put("widthPx", width).put("heightPx", height)
            .put("displayId", (current.view.display?.displayId ?: 0).coerceAtLeast(0))
            .put("densityDpi", configuration.densityDpi.coerceIn(72, 960))
            .put("fontScale", configuration.fontScale.coerceIn(0.5f, 3f).toDouble())
            .put("visible", true)
            .put("theme", when {
                colorScheme.background.toArgb() == android.graphics.Color.BLACK -> "amoled"
                colorScheme.background.luminance() < 0.5f -> "dark"
                else -> "light"
            })
            .put("colors", colors)
            .put("localeTags", configuration.locales.toLanguageTags().take(256)).toString()
        try { SurfaceBinderCalls.run(5_000) { current.session.updateHost(update) } }
        catch (_: Exception) { if (lease === current) { current.detach(); lease = null; message = "The add-on surface stopped responding" } }
        }
    }
    LaunchedEffect(active, module?.status, retryGeneration) {
        if (active && module?.status == ModuleConnectionStatus.FAILED && automaticRetries < 2) {
            delay(if (automaticRetries == 0) 700 else 1_500)
            automaticRetries++
            onRetry()
        }
    }
    BackHandler(enabled = active) {
        val current = lease
        if (current == null) { onNavigate("home"); return@BackHandler }
        scope.launch(SurfaceUiThread.dispatcher) {
            val consumed = runCatching { SurfaceBinderCalls.run(3_000) { current.session.dispatchBack() } }.getOrDefault(false)
            if (verifiedSession(current) && !consumed) onNavigate("home")
        }
    }

    if (Build.VERSION.SDK_INT >= 30 && canShow) {
        Box(viewportModifier) {
        AndroidView(factory = { ctx ->
            SurfaceView(ctx).also { view ->
                // The embedded hierarchy has its own input window. Keep its
                // SurfaceControl above Core's Compose window so real touches
                // reach the provider, while the view itself stays inside the
                // reserved workspace viewport.
                view.setZOrderOnTop(true)
                view.holder.setFormat(PixelFormat.TRANSLUCENT)
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                view.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        Log.d(SURFACE_LOG, "surface created")
                        surfaceGeneration++
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                        Log.d(SURFACE_LOG, "surface changed: ${w}x$h")
                        if (width == 0 || height == 0) surfaceGeneration++
                        width = w; height = h
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        Log.d(SURFACE_LOG, "surface destroyed")
                        width = 0; height = 0; surfaceGeneration++
                    }
                })
                surfaceView = view
            }
        }, modifier = Modifier.fillMaxSize(), onRelease = { view ->
            if (surfaceView === view) { surfaceView = null; width = 0; height = 0 }
        })
        if (lease == null && (loading || message != null)) {
            Column(Modifier.align(Alignment.Center).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading) CircularProgressIndicator()
                Text(message ?: "Opening add-on screen…")
                if (!loading && message != null) {
                    Button(onClick = { retryGeneration++; onRetry() }) { Text("Retry") }
                    OutlinedButton(onClick = onOpenModuleSettings) { Text("Add-on settings") }
                }
            }
        }
        }
    } else {
        Column(viewportModifier.padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(module?.label ?: "Add-on screen unavailable", style = MaterialTheme.typography.titleLarge)
            Text(message ?: unavailableReason ?: when {
                module == null -> "The add-on is no longer installed. Its Home placement is saved."
                Build.VERSION.SDK_INT < surfaceMinSdk ->
                    "This add-on needs Android API $surfaceMinSdk or newer for its embedded screen."
                module.status == ModuleConnectionStatus.DISABLED -> "Enable this add-on to open its screen."
                module.status == ModuleConnectionStatus.CONNECTING -> "Connecting to the add-on…"
                module.status == ModuleConnectionStatus.CONNECTED && route == null ->
                    "This add-on does not provide a native screen. Update the add-on or open its settings."
                else -> "The add-on screen is unavailable. Its Home placement is saved."
            }, modifier = Modifier.padding(vertical = 12.dp))
            if (loading) CircularProgressIndicator()
            if (module != null) {
                Button(onClick = { retryGeneration++; onRetry() }, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
                OutlinedButton(onClick = onOpenModuleSettings, modifier = Modifier.fillMaxWidth()) { Text("Add-on settings") }
                if (Build.VERSION.SDK_INT < surfaceMinSdk) module.descriptor?.workspaceActivity?.let { declaration ->
                    OutlinedButton(onClick = {
                        try {
                            val signer = AndroidModuleIdentityResolver(context)
                                .resolve(module.component.packageName).getOrThrow().key
                            check(signer == expectedSignerKey && module.component.flattenToString() == placementComponent)
                            val component = ModuleDescriptorDiscovery.validateManagementActivity(
                                context.packageManager, ServiceInfo().apply { packageName = module.component.packageName }, declaration)
                            context.startActivity(Intent().setComponent(component))
                        } catch (_: Exception) { message = "Could not open the add-on screen" }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Open add-on screen") }
                }
            }
        }
    }
}
