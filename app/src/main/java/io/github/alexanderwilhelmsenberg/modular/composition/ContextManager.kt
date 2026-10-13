package io.github.alexanderwilhelmsenberg.modular.composition

import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ContextManager(
    private val stateStore: CompositionStateStore,
    private val routes: StateFlow<List<CapabilityRoute>>,
) : AutoCloseable {
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activationError = MutableStateFlow<String?>(null)
    val activationError: StateFlow<String?> =
        _activationError.asStateFlow()

    val contexts: StateFlow<List<LauncherContext>> =
        stateStore.state
            .map { state -> state.contexts }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList(),
            )

    val active: StateFlow<ActiveContextState> =
        combine(
            stateStore.state,
            routes,
        ) { state, availableRoutes ->
            val activeContext = state.activeContextId
                ?.let { activeId ->
                    state.contexts.firstOrNull {
                        it.id == activeId
                    }
                }

            if (activeContext == null) {
                ActiveContextState.NONE
            } else {
                ProviderSelectionResolver.resolve(
                    context = activeContext,
                    instances = state.instances,
                    routes = availableRoutes,
                )
            }
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = ActiveContextState.NONE,
        )

    suspend fun create(
        name: String,
        bindings: List<ContextCapabilityBinding> =
            emptyList(),
    ): Result<LauncherContext> {
        val context = LauncherContext(
            id = UUID.randomUUID().toString(),
            name = name,
            bindings = bindings,
        )

        return upsert(context)
    }

    suspend fun upsert(
        context: LauncherContext,
    ): Result<LauncherContext> =
        runCatching {
            val normalized = normalize(context)

            stateStore.update { state ->
                validateAgainstState(normalized, state)

                if (state.activeContextId == normalized.id) {
                    val existing = requireNotNull(
                        state.contexts.firstOrNull {
                            it.id == normalized.id
                        },
                    ) {
                        "Active context is missing from persisted contexts"
                    }

                    require(
                        existing.bindings == normalized.bindings,
                    ) {
                        "Deactivate the active context before changing " +
                            "its capability bindings"
                    }
                }

                state.copy(
                    contexts = (
                        state.contexts.filterNot {
                            it.id == normalized.id
                        } + normalized
                    ).sortedBy { it.id },
                )
            }

            normalized
        }

    suspend fun remove(
        contextId: String,
    ): Result<Unit> =
        runCatching {
            require(contextId.isNotBlank()) {
                "Context id must not be blank"
            }

            stateStore.update { state ->
                state.copy(
                    contexts = state.contexts.filterNot {
                        it.id == contextId
                    },
                    activeContextId =
                        state.activeContextId
                            .takeUnless { it == contextId },
                )
            }

            _activationError.value = null
            Unit
        }

    suspend fun activate(
        contextId: String,
    ): Result<ActiveContextState> {
        val result = runCatching {
            require(contextId.isNotBlank()) {
                "Context id must not be blank"
            }

            var activated: ActiveContextState? = null

            stateStore.update { state ->
                val context = requireNotNull(
                    state.contexts.firstOrNull {
                        it.id == contextId
                    },
                ) {
                    "Unknown context " + contextId
                }

                validateAgainstState(context, state)

                val resolution = ProviderSelectionResolver.resolve(
                    context = context,
                    instances = state.instances,
                    routes = routes.value,
                )

                require(resolution.isReady) {
                    resolution.problems.joinToString(
                        separator = "; ",
                    ) { problem ->
                        problem.capability.wireValue +
                            ": " + problem.detail
                    }
                }

                activated = resolution

                state.copy(activeContextId = context.id)
            }

            requireNotNull(activated)
        }

        _activationError.value =
            result.exceptionOrNull()?.message

        return result
    }

    suspend fun deactivate(): Result<Unit> =
        runCatching {
            stateStore.update { state ->
                state.copy(activeContextId = null)
            }
            _activationError.value = null
            Unit
        }

    fun activeRoute(
        capability: CapabilityId,
    ): CapabilityRoute? =
        active.value.routeFor(capability)

    override fun close() {
        scope.cancel()
    }

    private fun normalize(
        context: LauncherContext,
    ): LauncherContext =
        context.copy(
            id = context.id.trim(),
            name = context.name.trim(),
            bindings = context.bindings
                .sortedBy { it.capability.wireValue },
        )

    private fun validateAgainstState(
        context: LauncherContext,
        state: CompositionState,
    ) {
        require(context.id.isNotBlank()) {
            "Context id must not be blank"
        }
        require(context.name.isNotBlank()) {
            "Context name must not be blank"
        }

        val duplicateCapabilities = context.bindings
            .groupBy { it.capability }
            .filterValues { bindings -> bindings.size > 1 }
            .keys

        require(duplicateCapabilities.isEmpty()) {
            "Context has duplicate capability bindings: " +
                duplicateCapabilities.joinToString {
                    it.wireValue
                }
        }

        val instanceIds = state.instances
            .map { it.id }
            .toSet()

        val missingInstances = context.bindings
            .map { it.instanceId }
            .filterNot { it in instanceIds }
            .distinct()

        require(missingInstances.isEmpty()) {
            "Context references missing instances: " +
                missingInstances.joinToString()
        }
    }
}
