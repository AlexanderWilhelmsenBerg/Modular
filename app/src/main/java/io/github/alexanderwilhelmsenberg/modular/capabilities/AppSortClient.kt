package io.github.alexanderwilhelmsenberg.modular.capabilities

import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppSortCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortResultParcel
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AppSortClient(
    private val capabilityRouter: CapabilityRouter,
) : AutoCloseable {
    // Remote Binder calls cannot be interrupted. One worker and one waiting
    // request bound the damage from a hung provider without racing sequential
    // calls that arrive as the worker completes.
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { task -> Thread(task, "modular-app-sort-ipc").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    suspend fun sort(
        route: CapabilityRoute,
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

        if (apps.size > AppSortRequestParcel.MAX_ITEMS) {
            return Result.failure(
                IllegalArgumentException(
                    "App count exceeds apps.sort/v1 maximum of " +
                        AppSortRequestParcel.MAX_ITEMS,
                ),
            )
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                worker.execute {
                    if (!continuation.isActive) return@execute
                    val result = runCatching {
                        val binder = runBlocking {
                            capabilityRouter.acquireModuleBinder(route).getOrThrow()
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
                    if (continuation.isActive) continuation.resume(result)
                }
            } catch (error: RejectedExecutionException) {
                if (continuation.isActive) continuation.resume(Result.failure(error))
            }
        }
    }

    override fun close() {
        worker.shutdown()
    }
}

