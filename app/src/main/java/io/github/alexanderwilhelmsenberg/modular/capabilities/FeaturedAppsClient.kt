package io.github.alexanderwilhelmsenberg.modular.capabilities

import io.github.alexanderwilhelmsenberg.modular.composition.ResolvedContextBinding
import io.github.alexanderwilhelmsenberg.modular.moduleapi.FeaturedAppsContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IFeaturedAppsCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedMutationResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedPinOrderRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedPinRequestParcel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Bounded, cancellable IPC for the selected featured-app instance. */
class FeaturedAppsClient(private val router: CapabilityRouter) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<PendingCall<*>>()

    suspend fun getFeaturedApps(
        route: CapabilityRoute,
        binding: ResolvedContextBinding,
        request: FeaturedAppsRequestParcel,
    ): Result<FeaturedAppsResultParcel> {
        runCatching { FeaturedAppsContract.validateRequest(request) }
            .onFailure { return Result.failure(it) }
        return call(route, binding) { it.getFeaturedApps(request) }
    }

    suspend fun setPinned(
        route: CapabilityRoute,
        binding: ResolvedContextBinding,
        request: FeaturedPinRequestParcel,
    ): Result<FeaturedMutationResultParcel> {
        runCatching { FeaturedAppsContract.validatePinRequest(request) }
            .onFailure { return Result.failure(it) }
        return call(route, binding) { it.setPinned(request) }
    }

    suspend fun replacePinOrder(
        route: CapabilityRoute,
        binding: ResolvedContextBinding,
        request: FeaturedPinOrderRequestParcel,
    ): Result<FeaturedMutationResultParcel> {
        runCatching { FeaturedAppsContract.validatePinOrderRequest(request) }
            .onFailure { return Result.failure(it) }
        return call(route, binding) { it.replacePinOrder(request) }
    }

    private suspend fun <T> call(
        route: CapabilityRoute,
        binding: ResolvedContextBinding,
        action: (IFeaturedAppsCapability) -> T,
    ): Result<T> {
        if (route.capability != StandardCapabilities.AppsFeatured ||
            route.providerKind != CapabilityProviderKind.MODULE ||
            binding.route != route || binding.capability != route.capability ||
            binding.instance.providerId != route.providerId
        ) return Result.failure(IllegalArgumentException("Featured binding does not match its route"))
        if (closed.get()) return Result.failure(IllegalStateException("Featured client is closed"))
        return suspendCancellableCoroutine { continuation ->
            val pending = PendingCall(continuation) {
                runCatching {
                    val binder = runBlocking {
                        router.acquireModuleBinder(
                            route, binding.instance.id, binding.instance.configurationJson,
                        ).getOrThrow()
                    }
                    action(IFeaturedAppsCapability.Stub.asInterface(binder))
                }
            }
            calls.add(pending)
            continuation.invokeOnCancellation { pending.cancel() }
            synchronized(pending) {
                if (closed.get()) pending.fail(IllegalStateException("Featured client is closed"))
                else if (!pending.settled.get()) runCatching { worker.execute(pending.task) }
                    .onFailure { pending.fail(it) }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            calls.toList().forEach { it.fail(IllegalStateException("Featured client is closed")) }
        }
    }

    private inner class PendingCall<T>(
        private val continuation: CancellableContinuation<Result<T>>,
        action: () -> Result<T>,
    ) {
        val settled = AtomicBoolean(false)
        val task = FutureTask<Unit>({ if (!settled.get()) complete(action()) }, Unit)
        fun complete(result: Result<T>) {
            calls.remove(this)
            if (settled.compareAndSet(false, true) && continuation.isActive) continuation.resume(result)
        }
        fun fail(error: Throwable) {
            task.cancel(false)
            worker.remove(task)
            complete(Result.failure(error))
        }
        fun cancel() {
            settled.set(true)
            calls.remove(this)
            task.cancel(false)
            worker.remove(task)
        }
    }

    private companion object {
        // Binder calls may ignore interruption. One process-wide worker caps blocked IPC
        // across Activity recreation and keeps the main thread free.
        val worker = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "modular-featured-ipc").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }
}
