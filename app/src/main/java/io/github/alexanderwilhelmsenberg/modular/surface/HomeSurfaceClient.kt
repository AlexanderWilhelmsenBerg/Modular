package io.github.alexanderwilhelmsenberg.modular.surface

import io.github.alexanderwilhelmsenberg.modular.capabilities.BoundedBinderCalls
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.capabilities.BoundedBinderCalls
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRouter
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.ISurfaceHomeCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceActionResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceDocumentParcel

class HomeSurfaceClient(
    private val capabilityRouter: CapabilityRouter,
) {
    suspend fun render(
        route: CapabilityRoute,
        instanceId: String,
    ): Result<SurfaceDocumentParcel> {
        requireHomeRoute(route)?.let {
            return Result.failure(it)
        }
        if (instanceId.isBlank()) {
            return Result.failure(
                IllegalArgumentException(
                    "Surface instance id must not be blank",
                ),
            )
        }

        return capabilityRouter
            .acquireModuleBinder(route)
            .fold(
                onSuccess = { binder ->
                    BoundedBinderCalls.call("home:${route.component}") {
                        runCatching {
                            val capability =
                                ISurfaceHomeCapability.Stub
                                    .asInterface(binder)

                            SurfaceDocumentValidator.validate(
                                capability.render(instanceId),
                            ).getOrThrow()
                        }.getOrThrow()
                    }
                },
                onFailure = { throwable ->
                    Result.failure(throwable)
                },
            )
    }

    suspend fun performAction(
        route: CapabilityRoute,
        instanceId: String,
        actionId: String,
    ): Result<Unit> {
        requireHomeRoute(route)?.let {
            return Result.failure(it)
        }
        if (instanceId.isBlank() || actionId.isBlank()) {
            return Result.failure(
                IllegalArgumentException(
                    "Surface instance id and action id " +
                        "must not be blank",
                ),
            )
        }

        return capabilityRouter
            .acquireModuleBinder(route)
            .fold(
                onSuccess = { binder ->
                    BoundedBinderCalls.call("home:${route.component}") {
                        runCatching {
                            val capability =
                                ISurfaceHomeCapability.Stub
                                    .asInterface(binder)
                            val result =
                                capability.performAction(
                                    instanceId,
                                    actionId,
                                )

                            require(
                                result.status ==
                                    SurfaceActionResultParcel
                                        .STATUS_SUCCESS,
                            ) {
                                result.message
                                    ?: "Surface action failed"
                            }
                        }.getOrThrow()
                    }
                },
                onFailure = { throwable ->
                    Result.failure(throwable)
                },
            )
    }

    private fun requireHomeRoute(
        route: CapabilityRoute,
    ): Throwable? =
        if (
            route.capability ==
            StandardCapabilities.SurfaceHome
        ) {
            null
        } else {
            IllegalArgumentException(
                "Route does not provide " +
                    StandardCapabilities
                        .SurfaceHome
                        .wireValue,
            )
        }
}
