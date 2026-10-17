package io.github.alexanderwilhelmsenberg.modular.capabilities

import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppSortCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortResultParcel
import java.util.Locale

class AppSortClient(
    private val capabilityRouter: CapabilityRouter,
) {
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

        if (apps.sumOf { it.key.packageName.length.toLong() + it.key.activityName.length + minOf(it.label.length, 512) } > 65_536) {
            return Result.failure(IllegalArgumentException("Catalog exceeds Sort transport budget"))
        }

        return capabilityRouter
            .acquireModuleBinder(route)
            .fold(
                onSuccess = { binder ->
                    BoundedBinderCalls.call("sort:${route.component}") {
                        runCatching {
                            val capability =
                                IAppSortCapability.Stub.asInterface(
                                    binder,
                                )

                            val result = capability.sort(
                                AppSortRequestParcel(
                                    items = apps.map { app ->
                                        AppSortItemParcel(
                                            key = AppKeyParcel(
                                                packageName =
                                                    app.key.packageName,
                                                activityName =
                                                    app.key.activityName,
                                                profileSerial =
                                                    app.key.profileSerial,
                                            ),
                                            label = app.label.take(512),
                                        )
                                    },
                                    localeTag = localeTag,
                                ),
                            )

                            require(
                                result.status ==
                                    AppSortResultParcel.STATUS_SUCCESS,
                            ) {
                                result.message
                                    ?: "Sort provider failed"
                            }

                            AppSortResultValidator.apply(
                                input = apps,
                                orderedKeys = result.orderedKeys,
                            ).getOrThrow()
                        }.getOrThrow()
                    }
                },
                onFailure = { throwable ->
                    Result.failure(throwable)
                },
            )
    }
}
