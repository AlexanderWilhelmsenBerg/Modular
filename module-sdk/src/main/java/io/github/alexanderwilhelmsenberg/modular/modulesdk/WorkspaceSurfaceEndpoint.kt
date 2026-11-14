package io.github.alexanderwilhelmsenberg.modular.modulesdk

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.alexanderwilhelmsenberg.modular.moduleapi.WorkspaceSurfaceContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.WorkspaceSurfaceHostSpec
import io.github.alexanderwilhelmsenberg.modular.moduleapi.WorkspaceSurfaceState
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IWorkspaceSurfaceCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IWorkspaceSurfaceHost
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IWorkspaceSurfaceSession
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Per-session API for an add-on-owned View hierarchy. All state methods are safe from the UI thread. */
class WorkspaceSurfaceScope internal constructor(
    val context: Context,
    initialHost: WorkspaceSurfaceHostSpec,
    private val callback: IWorkspaceSurfaceHost,
) {
    private val main = Handler(Looper.getMainLooper())
    private val hostFlow = MutableStateFlow(initialHost)
    val hostState: StateFlow<WorkspaceSurfaceHostSpec> = hostFlow
    private val closed = AtomicBoolean(false)
    private val cleanup = mutableListOf<() -> Unit>()
    private val cleanupLock = Any()
    @Volatile private var state = WorkspaceSurfaceState(false)
    @Volatile var onBackRequested: (() -> Boolean)? = null

    fun updateState(canGoBack: Boolean, exclusiveGestures: Boolean = true) {
        if (closed.get()) return
        state = WorkspaceSurfaceState(canGoBack, exclusiveGestures)
        runCatching { callback.onStateChanged(hostFlow.value.sessionId, WorkspaceSurfaceContract.encodeState(state)) }
    }

    fun requestNavigation(destination: String) {
        WorkspaceSurfaceContract.validateNavigationDestination(destination)
        if (!closed.get()) runCatching { callback.requestNavigation(hostFlow.value.sessionId, destination) }
    }

    fun requestActivity(nonce: String) {
        WorkspaceSurfaceContract.validateActivityNonce(nonce)
        if (!closed.get()) runCatching { callback.requestActivity(hostFlow.value.sessionId, nonce) }
    }

    fun registerOnClose(action: () -> Unit) {
        val now = synchronized(cleanupLock) {
            if (closed.get()) true else { cleanup += action; false }
        }
        if (now) runCleanupOnMain(listOf(action))
    }

    internal fun updateHost(spec: WorkspaceSurfaceHostSpec) { if (!closed.get()) hostFlow.value = spec }
    internal fun exclusiveGestures(): Boolean = state.exclusiveGestures
    internal fun close() {
        val actions = synchronized(cleanupLock) {
            if (!closed.compareAndSet(false, true)) return
            cleanup.toList().also { cleanup.clear() }
        }
        onBackRequested = null
        runCleanupOnMain(actions)
    }

    private fun runCleanupOnMain(actions: List<() -> Unit>) {
        if (actions.isEmpty()) return
        val drain = Runnable { actions.forEach { runCatching { it() } } }
        if (Looper.myLooper() == Looper.getMainLooper()) drain.run() else main.post(drain)
    }
}

/** Bounded and authenticated SurfaceControlViewHost factory for independently rendered module UI. */
abstract class WorkspaceSurfaceEndpoint(
    private val context: Context,
    private val instance: ModuleInstanceContext,
) : IWorkspaceSurfaceCapability.Stub() {
    private val main = Handler(Looper.getMainLooper())
    private val sessions = WorkspaceSurfaceSessionRegistry<Session>(MAX_ACTIVE_SURFACES)

    init { instance.onRevoke { revokeAll() } }

    protected abstract fun onCreateView(scope: WorkspaceSurfaceScope): View

    final override fun createSession(
        hostToken: IBinder?,
        hostJson: String,
        host: IWorkspaceSurfaceHost?,
    ): IWorkspaceSurfaceSession {
        instance.enforceCallingCore()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException("Native workspace surfaces require Android 11")
        }
        requireNotNull(hostToken) { "Missing surface host token" }
        requireNotNull(host) { "Missing surface host callback" }
        val spec = WorkspaceSurfaceContract.parseHostSpec(hostJson)
        val session = Session(hostToken, host, spec)
        sessions.add(session)
        try {
            host.asBinder().linkToDeath(session.deathRecipient, 0)
            if (!host.asBinder().isBinderAlive) throw SecurityException("Surface host died")
            session.onMain { session.createOnMain() }
            return session
        } catch (failure: Throwable) {
            session.dispose()
            throw failure
        }
    }

    private fun revokeAll() {
        val retired = sessions.closeAndDrain()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            retired.forEach { it.dispose() }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private inner class Session(
        private val token: IBinder,
        private val callback: IWorkspaceSurfaceHost,
        initialSpec: WorkspaceSurfaceHostSpec,
    ) : IWorkspaceSurfaceSession.Stub() {
        private val closed = AtomicBoolean(false)
        @Volatile private var scope: WorkspaceSurfaceScope? = null // Main thread creation, concurrent close
        private val sessionId = initialSpec.sessionId
        private val displayId = initialSpec.displayId
        private var surfaceHost: SurfaceControlViewHost? = null // Main thread only
        private var owner: SurfaceTreeOwner? = null // Main thread only
        private var spec: WorkspaceSurfaceHostSpec = initialSpec
        val deathRecipient = IBinder.DeathRecipient { dispose() }

        fun createOnMain() {
            instance.checkActive()
            if (closed.get()) return
            val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(spec.displayId) ?: throw IllegalArgumentException("Unknown workspace display")
            val viewContext = context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)
            val host = SurfaceControlViewHost(viewContext, display, token)
            surfaceHost = host
            try {
                val surfaceScope = WorkspaceSurfaceScope(viewContext, spec, callback)
                scope = surfaceScope
                if (closed.get()) { surfaceScope.close(); return }
                val treeOwner = SurfaceTreeOwner()
                owner = treeOwner
                treeOwner.start(spec.visible)
                val root = WorkspaceSurfaceGestureLayout(viewContext, surfaceScope)
                root.setViewTreeLifecycleOwner(treeOwner)
                root.setViewTreeSavedStateRegistryOwner(treeOwner)
                root.setViewTreeViewModelStoreOwner(treeOwner)
                root.setViewTreeOnBackPressedDispatcherOwner(treeOwner)
                surfaceScope.updateState(canGoBack = false)
                root.addView(onCreateView(surfaceScope))
                if (closed.get()) { surfaceScope.close(); return }
                host.setView(root, spec.widthPx, spec.heightPx)
            } catch (failure: Throwable) {
                destroyOnMain()
                throw failure
            }
        }

        override fun getSurfacePackage(): SurfaceControlViewHost.SurfacePackage? {
            instance.enforceCallingCore()
            check(!closed.get()) { "Surface session closed" }
            return onMain {
                check(!closed.get()) { "Surface session closed" }
                surfaceHost?.surfacePackage ?: throw IllegalStateException("Surface unavailable")
            }
        }

        override fun updateHost(hostJson: String) {
            instance.enforceCallingCore()
            check(!closed.get()) { "Surface session closed" }
            val next = WorkspaceSurfaceContract.parseHostSpec(hostJson)
            require(next.sessionId == sessionId && next.displayId == displayId) {
                "Workspace surface identity changed"
            }
            onMain {
                check(!closed.get()) { "Surface session closed" }
                val previous = spec
                instance.checkActive()
                spec = next
                scope?.updateHost(next)
                if (previous.widthPx != next.widthPx || previous.heightPx != next.heightPx) {
                    surfaceHost?.relayout(next.widthPx, next.heightPx)
                }
                owner?.setVisible(next.visible)
            }
        }

        override fun dispatchBack(): Boolean {
            instance.enforceCallingCore()
            check(!closed.get()) { "Surface session closed" }
            return onMain {
                check(!closed.get()) { "Surface session closed" }
                val dispatcher = owner?.onBackPressedDispatcher
                if (dispatcher?.hasEnabledCallbacks() == true) {
                    dispatcher.onBackPressed(); true
                } else scope?.onBackRequested?.invoke() == true
            }
        }

        override fun close() {
            instance.enforceCallingCore()
            dispose()
        }

        fun dispose() {
            if (!closed.compareAndSet(false, true)) return
            scope?.close()
            sessions.remove(this)
            runCatching { callback.asBinder().unlinkToDeath(deathRecipient, 0) }
            if (Looper.myLooper() == Looper.getMainLooper()) destroyOnMain()
            else main.post { destroyOnMain() }
        }

        private fun destroyOnMain() {
            scope?.close()
            scope = null
            owner?.destroy()
            owner = null
            surfaceHost?.release()
            surfaceHost = null
        }

        /** A timed out caller closes the session; a late package is released on Main. */
        fun <T> onMain(action: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return action()
            val result = CompletableFuture<T>()
            main.post {
                if (closed.get()) { result.completeExceptionally(IllegalStateException("Surface session closed")); return@post }
                try {
                    val value = action()
                    if (closed.get()) {
                        if (value is SurfaceControlViewHost.SurfacePackage) value.release()
                        result.completeExceptionally(IllegalStateException("Surface session closed"))
                    } else result.complete(value)
                } catch (failure: Throwable) { result.completeExceptionally(failure) }
            }
            return try { result.get(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            catch (failure: TimeoutException) {
                result.whenComplete { value, _ ->
                    if (value is SurfaceControlViewHost.SurfacePackage) value.release()
                }
                dispose()
                throw IllegalStateException("Surface main thread timed out", failure)
            }
        }
    }

    private class SurfaceTreeOwner : LifecycleOwner, SavedStateRegistryOwner,
        ViewModelStoreOwner, OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore = ViewModelStore()
        override val onBackPressedDispatcher = OnBackPressedDispatcher()

        fun start(visible: Boolean) {
            saved.performAttach()
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
            setVisible(visible)
        }
        fun setVisible(visible: Boolean) {
            registry.currentState = if (visible) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        }
        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }

    companion object {
        const val MAX_ACTIVE_SURFACES = 2
        const val MAIN_TIMEOUT_MS = 2_000L
    }
}
