package io.github.alexanderwilhelmsenberg.modular.composition

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class InstanceStore(
    private val stateStore: CompositionStateStore,
) : AutoCloseable {
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val instances: StateFlow<List<ModuleInstance>> =
        stateStore.state
            .map { state -> state.instances }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList(),
            )

    suspend fun create(
        name: String,
        providerId: String,
        configurationJson: String = "{}",
    ): Result<ModuleInstance> =
        runCatching {
            val instance = normalizeAndValidate(
                ModuleInstance(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    providerId = providerId,
                    configurationJson = configurationJson,
                ),
            )

            stateStore.update { state ->
                require(
                    state.instances.none { it.id == instance.id },
                ) {
                    "Generated duplicate instance id " + instance.id
                }

                state.copy(
                    instances = (state.instances + instance)
                        .sortedBy { it.id },
                )
            }

            instance
        }

    suspend fun upsert(
        instance: ModuleInstance,
    ): Result<ModuleInstance> =
        runCatching {
            val normalized = normalizeAndValidate(instance)

            stateStore.update { state ->
                val existing = state.instances.firstOrNull {
                    it.id == normalized.id
                }

                require(
                    existing == null ||
                        existing.providerId == normalized.providerId,
                ) {
                    "Instance provider identity is immutable; " +
                        "create a new instance to select a different provider"
                }

                state.copy(
                    instances = (
                        state.instances.filterNot {
                            it.id == normalized.id
                        } + normalized
                    ).sortedBy { it.id },
                )
            }

            normalized
        }

    suspend fun remove(
        instanceId: String,
    ): Result<Unit> =
        runCatching {
            require(instanceId.isNotBlank()) {
                "Instance id must not be blank"
            }

            stateStore.update { state ->
                val references = state.contexts
                    .filter { context ->
                        context.bindings.any {
                            it.instanceId == instanceId
                        }
                    }
                    .map { it.name }

                require(references.isEmpty()) {
                    "Instance is referenced by contexts: " +
                        references.joinToString()
                }

                state.copy(
                    instances = state.instances.filterNot {
                        it.id == instanceId
                    },
                )
            }

            Unit
        }

    fun find(instanceId: String): ModuleInstance? =
        stateStore.state.value.instances
            .firstOrNull { it.id == instanceId }

    override fun close() {
        scope.cancel()
    }

    private fun normalizeAndValidate(
        instance: ModuleInstance,
    ): ModuleInstance {
        val normalized = instance.copy(
            id = instance.id.trim(),
            name = instance.name.trim(),
            providerId = instance.providerId.trim(),
            configurationJson =
                instance.configurationJson.trim(),
        )

        require(normalized.id.isNotBlank()) {
            "Instance id must not be blank"
        }
        require(normalized.name.isNotBlank()) {
            "Instance name must not be blank"
        }
        require(normalized.providerId.isNotBlank()) {
            "Instance provider id must not be blank"
        }

        CompositionStateCodec.validateConfigurationJson(
            normalized.configurationJson,
        )

        return normalized
    }
}
