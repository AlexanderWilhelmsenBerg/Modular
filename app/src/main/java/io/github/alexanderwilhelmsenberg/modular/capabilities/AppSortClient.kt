package io.github.alexanderwilhelmsenberg.modular.capabilities

import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppSortCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortResultParcel
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

        return capabilityRouter
            .acquireModuleBinder(route)
            .fold(
                onSuccess = { binder ->
                    withContext(Dispatchers.IO) {
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
                                            label = app.label,
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
                        }
                    }
                },
                onFailure = { throwable ->
                    Result.failure(throwable)
                },
            )
    }
}
