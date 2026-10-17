package io.github.alexanderwilhelmsenberg.modular.composition

import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

data class CompositionProvider(
    val capability: CapabilityId,
    val providerId: String,
    val configurationJson: String = "{}",
)

/** Atomically creates a complete launcher composition without module IDs in Core. */
class LauncherCompositionSetup(
    private val store: CompositionStateStore,
    private val routes: StateFlow<List<CapabilityRoute>>,
) {
    suspend fun createAndActivate(name: String, providers: List<CompositionProvider>): Result<ActiveContextState> = try {
        require(name.isNotBlank()) { "Context name is required" }
        require(providers.isNotEmpty())
        require(providers.map { it.capability }.toSet().size == providers.size) { "Duplicate capability bindings" }
        require(providers.any { it.capability == StandardCapabilities.SurfaceHome }) { "Choose a Home provider" }
        val instances = providers.map {
            require(it.providerId.isNotBlank())
            require(it.configurationJson.length <= 8192) { "Configuration is too large" }
            CompositionStateCodec.validateConfigurationJson(it.configurationJson)
            ModuleInstance(UUID.randomUUID().toString(), name.trim() + " " + it.capability.name.substringAfterLast('.'),
                it.providerId, it.configurationJson)
        }
        val context = LauncherContext(UUID.randomUUID().toString(), name.trim(),
            providers.zip(instances).map { (provider, instance) -> ContextCapabilityBinding(provider.capability, instance.id) })
        var resolved: ActiveContextState? = null
        store.update { old ->
            val available = routes.value
            providers.forEach { provider ->
                require(available.count { it.capability == provider.capability &&
                    it.providerId == provider.providerId && it.providerKind == CapabilityProviderKind.MODULE } == 1) {
                    "Provider is unavailable or ambiguous: ${provider.providerId}"
                }
            }
            val next = old.copy(instances = (old.instances + instances).sortedBy { it.id },
                contexts = (old.contexts + context).sortedBy { it.id }, activeContextId = context.id)
            resolved = ProviderSelectionResolver.resolve(context, next.instances, available)
            require(requireNotNull(resolved).isReady)
            next
        }
        Result.success(requireNotNull(resolved))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    suspend fun removeBinding(contextId: String, capability: CapabilityId): Result<Unit> = try {
        store.update { old ->
            val context = requireNotNull(old.contexts.find { it.id == contextId }) { "Context no longer exists" }
            val binding = requireNotNull(context.bindings.singleOrNull { it.capability == capability }) {
                "Capability binding no longer exists or is ambiguous"
            }
            val contexts = old.contexts.map {
                if (it.id == contextId) it.copy(bindings = it.bindings.filterNot { it.capability == capability }) else it
            }
            val stillUsed = contexts.any { candidate -> candidate.bindings.any { it.instanceId == binding.instanceId } }
            old.copy(contexts = contexts,
                instances = if (stillUsed) old.instances else old.instances.filterNot { it.id == binding.instanceId },
                activeContextId = old.activeContextId.takeUnless { it == contextId })
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    suspend fun removeContext(contextId: String): Result<Unit> = try {
        store.update { old ->
            val context = requireNotNull(old.contexts.find { it.id == contextId }) { "Context no longer exists" }
            val remaining = old.contexts.filterNot { it.id == contextId }
            val stillUsed = remaining.flatMap { it.bindings }.map { it.instanceId }.toSet()
            val unused = context.bindings.map { it.instanceId }.filterNot { it in stillUsed }.toSet()
            old.copy(contexts = remaining, instances = old.instances.filterNot { it.id in unused },
                activeContextId = old.activeContextId.takeUnless { it == contextId })
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}
