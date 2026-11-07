package io.github.alexanderwilhelmsenberg.modular.composition

import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
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
            ?.sortedByDescending { it.capability == StandardCapabilities.AppsFeatured || it.capability == StandardCapabilities.AppsSort }
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
        val selectedIds = state.activeContext()?.bindings.orEmpty()
            .filter { it.capability == StandardCapabilities.AppsSort || it.capability == StandardCapabilities.AppsFeatured }
            .map { it.instanceId }.toSet()
        return state.instances.any { it.id in selectedIds && it.providerId == providerId }
    }

    /** Capture the exact selected Home context before copying settings to Core. */
    fun selectedHomeContextFor(binding: ResolvedContextBinding): LauncherContext? =
        stateStore.state.value.let { state ->
            state.activeContext()?.takeIf { context ->
                selectedHomeBindingMatches(state, context, binding) && binding.route in routes.value
            }
        }

    /** Detach only the previously validated selection, leaving instances and other contexts intact. */
    suspend fun detachSelectedHomeBinding(
        binding: ResolvedContextBinding,
        expectedContext: LauncherContext,
    ): Result<Boolean> = runCatching {
        var detached = false
        stateStore.update { state ->
            val context = state.activeContext() ?: return@update state
            if (context != expectedContext || !selectedHomeBindingMatches(state, context, binding) ||
                binding.route !in routes.value) return@update state
            val existingHome = state.contexts.firstOrNull { it.id == HOME_CONTEXT_ID }
            require(existingHome == null || existingHome.name == HOME_CONTEXT_NAME) {
                "Managed Home context id conflicts with another context"
            }
            val selected = ContextCapabilityBinding(binding.capability, binding.instance.id)
            val home = LauncherContext(
                id = HOME_CONTEXT_ID,
                name = HOME_CONTEXT_NAME,
                bindings = context.bindings.filterNot { it == selected },
            )
            detached = true
            state.copy(
                contexts = (state.contexts.filterNot { it.id == HOME_CONTEXT_ID } + home)
                    .sortedBy { it.id },
                activeContextId = HOME_CONTEXT_ID,
            )
        }
        detached
    }.onFailure { if (it is CancellationException) throw it }

    private fun selectedHomeBindingMatches(
        state: CompositionState,
        context: LauncherContext,
        binding: ResolvedContextBinding,
    ): Boolean {
        if (binding.capability != StandardCapabilities.AppsFeatured &&
            binding.capability != StandardCapabilities.AppsSort) return false
        if (binding.route.capability != binding.capability) return false
        val selected = ContextCapabilityBinding(binding.capability, binding.instance.id)
        val homeBindings = context.bindings.filter {
            it.capability == StandardCapabilities.AppsFeatured || it.capability == StandardCapabilities.AppsSort
        }
        if (homeBindings != listOf(selected)) return false
        val current = state.instances.singleOrNull { it.id == binding.instance.id }
            ?: return false
        if (current.providerId != binding.route.providerId) return false
        return runCatching { current.withValidatedConfiguration(listOf(binding.route)) }.getOrNull() ==
            binding.instance
    }

    /** Upgrade a selected legacy provider, retaining its instance and any advanced context. */
    suspend fun migrateActiveLegacySortToFeatured(): Result<Boolean> = runCatching {
        val initial = stateStore.state.value
        val initialContext = initial.activeContext() ?: return@runCatching false
        if (initialContext.bindings.none { it.capability == StandardCapabilities.AppsSort } ||
            initialContext.bindings.any { it.capability == StandardCapabilities.AppsFeatured }) return@runCatching false
        var migrated = false
        stateStore.update { state ->
            val context = state.activeContext() ?: return@update state
            if (context.bindings.any { it.capability == StandardCapabilities.AppsFeatured }) return@update state
            val old = context.bindings.firstOrNull { it.capability == StandardCapabilities.AppsSort }
                ?: return@update state
            val instance = state.instances.firstOrNull { it.id == old.instanceId } ?: return@update state
            val featuredRoutes = routes.value.filter {
                it.providerKind == CapabilityProviderKind.MODULE &&
                    it.providerId == instance.providerId && it.capability == StandardCapabilities.AppsFeatured
            }
            if (featuredRoutes.size != 1) return@update state
            val nextContext = context.copy(bindings = context.bindings.map {
                if (it == old) ContextCapabilityBinding(StandardCapabilities.AppsFeatured, old.instanceId) else it
            }.sortedBy { it.capability.wireValue })
            val home = if (context.id == HOME_CONTEXT_ID) nextContext else LauncherContext(
                HOME_CONTEXT_ID, HOME_CONTEXT_NAME, nextContext.bindings,
            )
            val existingHome = state.contexts.firstOrNull { it.id == HOME_CONTEXT_ID }
            if (existingHome != null && existingHome.name != HOME_CONTEXT_NAME) return@update state
            if (!ProviderSelectionResolver.resolve(home, state.instances, routes.value).isReady) return@update state
            migrated = true
            state.copy(
                contexts = (state.contexts.filterNot { it.id == HOME_CONTEXT_ID } + home).sortedBy { it.id },
                activeContextId = HOME_CONTEXT_ID,
            )
        }
        migrated
    }.onFailure { if (it is CancellationException) throw it }

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

            val currentContext = state.activeContext()
            // A migrated Home binding may still use a user-created instance ID.
            // Keep it so provider-owned pins remain attached to that instance.
            val id = currentContext
                ?.takeIf { it.id == HOME_CONTEXT_ID }
                ?.bindings
                ?.firstOrNull {
                    (it.capability == StandardCapabilities.AppsFeatured || it.capability == StandardCapabilities.AppsSort) &&
                        state.instances.any { instance ->
                            instance.id == it.instanceId && instance.providerId == normalizedProviderId
                        }
                }
                ?.instanceId ?: instanceId(normalizedProviderId)
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
            val selectedInstanceIds = currentContext?.bindings.orEmpty()
                .filter { it.capability == StandardCapabilities.AppsSort || it.capability == StandardCapabilities.AppsFeatured }
                .map { it.instanceId }.toSet()
            val wasSelected = state.instances.any { it.id in selectedInstanceIds && it.providerId == normalizedProviderId }

            if (!useForAppOrder && !wasSelected) {
                state.copy(instances = nextInstances)
            } else {
                val selectedCapability = when {
                    providerRoutes.count { it.capability == StandardCapabilities.AppsFeatured } == 1 -> StandardCapabilities.AppsFeatured
                    providerRoutes.count { it.capability == StandardCapabilities.AppsSort } == 1 -> StandardCapabilities.AppsSort
                    else -> null
                }
                if (useForAppOrder) require(selectedCapability != null) {
                    "Module provider does not have a unique Home apps route"
                }

                // Copy the active context so a user's advanced context is never edited in place.
                // When Home is already active, preserve its other capability bindings.
                val existingHome = state.contexts.firstOrNull { it.id == HOME_CONTEXT_ID }
                require(existingHome == null || existingHome.name == HOME_CONTEXT_NAME) {
                    "Managed Home context id conflicts with another context"
                }
                val sourceBindings = currentContext?.bindings.orEmpty()
                val bindings = sourceBindings.filterNot {
                    it.capability == StandardCapabilities.AppsSort || it.capability == StandardCapabilities.AppsFeatured
                } + if (useForAppOrder) {
                    listOf(ContextCapabilityBinding(requireNotNull(selectedCapability), id))
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
    }.onFailure { if (it is CancellationException) throw it }

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
