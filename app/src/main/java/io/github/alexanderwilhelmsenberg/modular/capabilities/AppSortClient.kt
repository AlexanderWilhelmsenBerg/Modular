package io.github.alexanderwilhelmsenberg.modular.capabilities

import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.composition.ResolvedContextBinding
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppSortCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortResultParcel
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AppSortClient(
    private val capabilityRouter: CapabilityRouter,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<PendingCall>()

    suspend fun sort(
        route: CapabilityRoute,
        binding: ResolvedContextBinding,
        apps: List<LaunchableApp>,
        localeTag: String = Locale.getDefault().toLanguageTag(),
    ): Result<List<LaunchableApp>> {
        if (route.capability != StandardCapabilities.AppsSort) {
            return Result.failure(
                IllegalArgumentException(
                    "Route does not provide " +
                        StandardCapabilities.AppsSort.wireValue,
                ),
            )
        }

        if (binding.route != route || binding.capability != route.capability ||
            binding.instance.providerId != route.providerId) {
            return Result.failure(IllegalArgumentException("Sort binding does not match its route"))
        }

        if (apps.size > AppSortRequestParcel.MAX_ITEMS) {
            return Result.failure(
                IllegalArgumentException(
                    "App count exceeds apps.sort/v1 maximum of " +
                        AppSortRequestParcel.MAX_ITEMS,
                ),
            )
        }

        if (closed.get()) return Result.failure(IllegalStateException("Sort client is closed"))

        return suspendCancellableCoroutine { continuation ->
            val call = PendingCall(continuation) {
                runCatching {
                    val binder = runBlocking {
                        capabilityRouter.acquireModuleBinder(
                            route,
                            binding.instance.id,
                            binding.instance.configurationJson,
                        ).getOrThrow()
                    }
                    val capability = IAppSortCapability.Stub.asInterface(binder)
                    val response = capability.sort(
                        AppSortRequestParcel(
                            items = apps.map { app ->
                                AppSortItemParcel(
                                    key = AppKeyParcel(
                                        packageName = app.key.packageName,
                                        activityName = app.key.activityName,
                                        profileSerial = app.key.profileSerial,
                                    ),
                                    label = app.label,
                                )
                            },
                            localeTag = localeTag,
                        ),
                    )
                    require(response.status == AppSortResultParcel.STATUS_SUCCESS) {
                        response.message ?: "Sort provider failed"
                    }
                    AppSortResultValidator.apply(apps, response.orderedKeys).getOrThrow()
                }
            }
            calls.add(call)
            continuation.invokeOnCancellation { call.cancel() }
            synchronized(call) {
                if (closed.get()) {
                    call.fail(IllegalStateException("Sort client is closed"))
                } else if (!call.settled.get()) {
                    try {
                        worker.execute(call.task)
                    } catch (error: RejectedExecutionException) {
                        call.fail(error)
                    }
                }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            calls.toList().forEach { it.fail(IllegalStateException("Sort client is closed")) }
        }
    }

    private inner class PendingCall(
        private val continuation: CancellableContinuation<Result<List<LaunchableApp>>>,
        action: () -> Result<List<LaunchableApp>>,
    ) {
        val settled = AtomicBoolean(false)
        val task = FutureTask<Unit>({
            if (!settled.get()) complete(action())
        }, Unit)

        fun complete(result: Result<List<LaunchableApp>>) {
            calls.remove(this)
            if (settled.compareAndSet(false, true) && continuation.isActive) {
                continuation.resume(result)
            }
        }

        fun fail(error: Throwable) = synchronized(this) {
            task.cancel(false)
            worker.remove(task)
            complete(Result.failure(error))
        }

        fun cancel() = synchronized(this) {
            task.cancel(false)
            worker.remove(task)
            calls.remove(this)
            settled.set(true)
        }
    }

    private companion object {
        // A timed-out Binder transaction can remain stuck. Keep the cap across
        // Activity recreation; the queued slot is removed on cancellation.
        val worker = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            { task -> Thread(task, "modular-app-sort-ipc").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }
}

