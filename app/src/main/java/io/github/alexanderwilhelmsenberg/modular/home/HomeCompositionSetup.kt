package io.github.alexanderwilhelmsenberg.modular.home

import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.composition.ContextCapabilityBinding
import io.github.alexanderwilhelmsenberg.modular.composition.ContextManager
import io.github.alexanderwilhelmsenberg.modular.composition.InstanceStore
import io.github.alexanderwilhelmsenberg.modular.composition.LauncherContext
import io.github.alexanderwilhelmsenberg.modular.composition.ModuleInstance
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import kotlinx.coroutines.flow.StateFlow

/**
 * Creates the minimal persisted composition needed to activate a Home provider.
 *
 * This is intentionally provider-agnostic. Core knows that a launcher needs a
 * surface.home capability, but it does not know or special-case any module ID.
 */
class HomeCompositionSetup(
    private val instances: InstanceStore,
    private val contexts: ContextManager,
    private val routes: StateFlow<List<CapabilityRoute>>,
) {
    suspend fun createAndActivate(
        providerId: String,
        name: String,
    ): Result<ActiveContextState> {
        val normalizedProviderId = providerId.trim()
        val normalizedName = name.trim()

        var createdInstance: ModuleInstance? = null
        var createdContext: LauncherContext? = null

        val result = runCatching {
            require(normalizedProviderId.isNotBlank()) {
                "Home provider id must not be blank"
            }
            require(normalizedName.isNotBlank()) {
                "Home context name must not be blank"
            }

            val liveProviders = routes.value.filter { route ->
                route.capability ==
                    StandardCapabilities.SurfaceHome &&
                    route.providerKind ==
                    CapabilityProviderKind.MODULE &&
                    route.providerId ==
                    normalizedProviderId
            }

            require(liveProviders.size == 1) {
                when {
                    liveProviders.isEmpty() ->
                        "Home provider is not currently available: " +
                            normalizedProviderId

                    else ->
                        "Home provider identity is ambiguous: " +
                            normalizedProviderId
                }
            }

            val instance = instances.create(
                name = normalizedName + " Home",
                providerId = normalizedProviderId,
            ).getOrThrow()
            createdInstance = instance

            val context = contexts.create(
                name = normalizedName,
                bindings = listOf(
                    ContextCapabilityBinding(
                        capability =
                            StandardCapabilities.SurfaceHome,
                        instanceId = instance.id,
                    ),
                ),
            ).getOrThrow()
            createdContext = context

            contexts.activate(context.id).getOrThrow()
        }

        if (result.isFailure) {
            val failure = requireNotNull(result.exceptionOrNull())

            createdContext?.let { context ->
                contexts.remove(context.id)
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }

            createdInstance?.let { instance ->
                instances.remove(instance.id)
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
        }

        return result
    }
}
