package io.github.alexanderwilhelmsenberg.modular.composition

import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import java.security.MessageDigest
import kotlinx.coroutines.flow.StateFlow

/** The ordinary Settings path for a module's saved configuration and Home app order. */
class ModuleSettingsController(
    private val stateStore: CompositionStateStore,
    private val routes: StateFlow<List<CapabilityRoute>>,
) {
    fun configurationFor(providerId: String): String {
        val state = stateStore.state.value
        val active = state.activeContext()
        val activeInstance = active?.bindings
            ?.sortedByDescending { it.capability == StandardCapabilities.AppsSort }
            ?.firstNotNullOfOrNull { binding ->
                state.instances.firstOrNull {
                    it.id == binding.instanceId && it.providerId == providerId
                }
            }

        return activeInstance?.configurationJson
            ?: state.instances.firstOrNull { it.id == instanceId(providerId) && it.providerId == providerId }
                ?.configurationJson
            ?: "{}"
    }

    fun isUsedForAppOrder(providerId: String): Boolean {
        val state = stateStore.state.value
        val sortInstanceId = state.activeContext()?.bindings
            ?.firstOrNull { it.capability == StandardCapabilities.AppsSort }
            ?.instanceId
        return state.instances.any { it.id == sortInstanceId && it.providerId == providerId }
    }

    suspend fun save(
        providerId: String,
        displayName: String,
        configurationJson: String,
        useForAppOrder: Boolean,
    ): Result<Unit> = runCatching {
        val normalizedProviderId = providerId.trim()
        val normalizedName = displayName.trim()
        require(normalizedProviderId.isNotEmpty()) { "Provider id must not be blank" }
        require(normalizedName.isNotEmpty()) { "Module name must not be blank" }

        stateStore.update { state ->
            val liveRoutes = routes.value
            val providerRoutes = liveRoutes.filter {
                it.providerId == normalizedProviderId &&
                    it.providerKind == CapabilityProviderKind.MODULE
            }
            require(providerRoutes.isNotEmpty()) {
                "Module provider is not currently available"
            }
            require(providerRoutes.groupBy { it.capability }.values.all { it.size == 1 }) {
                "Module provider has ambiguous live routes"
            }

            val id = instanceId(normalizedProviderId)
            val existing = state.instances.firstOrNull { it.id == id }
            require(existing == null || existing.providerId == normalizedProviderId) {
                "Managed module instance id conflicts with another provider"
            }
            val instance = ModuleInstance(
                id = id,
                name = normalizedName,
                providerId = normalizedProviderId,
                configurationJson = configurationJson.trim(),
            ).withValidatedConfiguration(providerRoutes)

            val nextInstances = (
                state.instances.filterNot { it.id == id } + instance
            ).sortedBy { it.id }
            val currentContext = state.activeContext()
            val currentSortInstance = currentContext?.bindings
                ?.firstOrNull { it.capability == StandardCapabilities.AppsSort }
                ?.let { binding -> state.instances.firstOrNull { it.id == binding.instanceId } }
            val wasSelected = currentSortInstance?.providerId == normalizedProviderId

            if (!useForAppOrder && !wasSelected) {
                state.copy(instances = nextInstances)
            } else {
                if (useForAppOrder) {
                    require(providerRoutes.count { it.capability == StandardCapabilities.AppsSort } == 1) {
                        "Module provider does not have a unique apps.sort route"
                    }
                }

                // Copy the active context so a user's advanced context is never edited in place.
                // When Home is already active, preserve its other capability bindings.
                val existingHome = state.contexts.firstOrNull { it.id == HOME_CONTEXT_ID }
                require(existingHome == null || existingHome.name == HOME_CONTEXT_NAME) {
                    "Managed Home context id conflicts with another context"
                }
                val sourceBindings = currentContext?.bindings.orEmpty()
                val bindings = sourceBindings.filterNot {
                    it.capability == StandardCapabilities.AppsSort
                } + if (useForAppOrder) {
                    listOf(ContextCapabilityBinding(StandardCapabilities.AppsSort, id))
                } else {
                    emptyList()
                }
                val home = LauncherContext(
                    id = HOME_CONTEXT_ID,
                    name = HOME_CONTEXT_NAME,
                    bindings = bindings.sortedBy { it.capability.wireValue },
                )
                val resolution = ProviderSelectionResolver.resolve(home, nextInstances, liveRoutes)
                require(resolution.isReady) {
                    resolution.problems.joinToString("; ") { it.detail }
                }
                state.copy(
                    instances = nextInstances,
                    contexts = (state.contexts.filterNot { it.id == HOME_CONTEXT_ID } + home)
                        .sortedBy { it.id },
                    activeContextId = HOME_CONTEXT_ID,
                )
            }
        }
        Unit
    }

    private fun CompositionState.activeContext(): LauncherContext? =
        contexts.firstOrNull { it.id == activeContextId }

    private fun instanceId(providerId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(providerId.toByteArray(Charsets.UTF_8))
        return "module-settings:" + digest.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private companion object {
        const val HOME_CONTEXT_ID = "module-settings:home:v1"
        const val HOME_CONTEXT_NAME = "Home"
    }
}
