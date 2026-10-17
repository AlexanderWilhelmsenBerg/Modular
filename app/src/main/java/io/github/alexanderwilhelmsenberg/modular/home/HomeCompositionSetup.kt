package io.github.alexanderwilhelmsenberg.modular.home

import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.composition.CompositionStateStore
import io.github.alexanderwilhelmsenberg.modular.composition.ContextCapabilityBinding
import io.github.alexanderwilhelmsenberg.modular.composition.LauncherContext
import io.github.alexanderwilhelmsenberg.modular.composition.ModuleInstance
import io.github.alexanderwilhelmsenberg.modular.composition.ProviderSelectionResolver
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/** Publishes instance, context and activation in one atomic store update. */
class HomeCompositionSetup(
    private val stateStore: CompositionStateStore,
    private val routes: StateFlow<List<CapabilityRoute>>,
) {
    suspend fun createAndActivate(
        providerId: String,
        name: String,
    ): Result<ActiveContextState> = try {
        val normalizedProviderId = providerId.trim()
        val normalizedName = name.trim()
        require(normalizedProviderId.isNotBlank()) { "Home provider id must not be blank" }
        require(normalizedName.isNotBlank()) { "Home context name must not be blank" }
        val instance = ModuleInstance(
            id = UUID.randomUUID().toString(),
            name = "$normalizedName Home",
            providerId = normalizedProviderId,
        )
        val context = LauncherContext(
            id = UUID.randomUUID().toString(),
            name = normalizedName,
            bindings = listOf(ContextCapabilityBinding(StandardCapabilities.SurfaceHome, instance.id)),
        )
        var activated: ActiveContextState? = null
        stateStore.update { state ->
            // Check liveness after waiting for the store transaction.
            val availableRoutes = routes.value
            val liveProviders = availableRoutes.filter {
                it.capability == StandardCapabilities.SurfaceHome &&
                    it.providerKind == CapabilityProviderKind.MODULE &&
                    it.providerId == normalizedProviderId
            }
            require(liveProviders.size == 1) {
                if (liveProviders.isEmpty()) {
                    "Home provider is not currently available: $normalizedProviderId"
                } else {
                    "Home provider identity is ambiguous: $normalizedProviderId"
                }
            }
            val next = state.copy(
                instances = (state.instances + instance).sortedBy { it.id },
                contexts = (state.contexts + context).sortedBy { it.id },
                activeContextId = context.id,
            )
            activated = ProviderSelectionResolver.resolve(context, next.instances, availableRoutes)
            require(requireNotNull(activated).isReady) { "Home provider could not be resolved" }
            next
        }
        Result.success(requireNotNull(activated))
    } catch (cancelled: CancellationException) {
        // Commit/return cancellation leaves either the old state or the full
        // new composition. No cleanup needs a live Activity or open store.
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}
