package io.github.alexanderwilhelmsenberg.modular.composition

import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object CompositionStateCodec {
    private const val CURRENT_SCHEMA_VERSION = 1

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(state: CompositionState): String =
        json.encodeToString(
            PersistedCompositionState.serializer(),
            state.toPersisted(),
        )

    fun decode(raw: String?): CompositionState {
        if (raw.isNullOrBlank()) return CompositionState()

        val persisted = json.decodeFromString(
            PersistedCompositionState.serializer(),
            raw,
        )

        require(
            persisted.schemaVersion in 1..CURRENT_SCHEMA_VERSION,
        ) {
            "Unsupported composition schema version " +
                persisted.schemaVersion
        }

        return persisted.toDomain()
    }

    fun validateConfigurationJson(value: String) {
        require(value.isNotBlank()) {
            "Instance configuration JSON must not be blank"
        }

        val element = json.parseToJsonElement(value)

        require(element is kotlinx.serialization.json.JsonObject) {
            "Instance configuration must be a JSON object"
        }
    }

    private fun CompositionState.toPersisted() =
        PersistedCompositionState(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            instances = instances
                .sortedBy { it.id }
                .map { instance ->
                    PersistedModuleInstance(
                        id = instance.id,
                        name = instance.name,
                        providerId = instance.providerId,
                        configurationJson = instance.configurationJson,
                    )
                },
            contexts = contexts
                .sortedBy { it.id }
                .map { context ->
                    PersistedLauncherContext(
                        id = context.id,
                        name = context.name,
                        bindings = context.bindings
                            .sortedBy { it.capability.wireValue }
                            .map { binding ->
                                PersistedContextBinding(
                                    capability =
                                        binding.capability.wireValue,
                                    instanceId = binding.instanceId,
                                )
                            },
                    )
                },
            activeContextId = activeContextId,
        )

    private fun PersistedCompositionState.toDomain(): CompositionState =
        CompositionState(
            instances = instances.map { instance ->
                ModuleInstance(
                    id = instance.id,
                    name = instance.name,
                    providerId = instance.providerId,
                    configurationJson = instance.configurationJson,
                )
            },
            contexts = contexts.map { context ->
                LauncherContext(
                    id = context.id,
                    name = context.name,
                    bindings = context.bindings.map { binding ->
                        ContextCapabilityBinding(
                            capability = requireNotNull(
                                CapabilityId.parse(binding.capability),
                            ) {
                                "Invalid persisted capability " +
                                    binding.capability
                            },
                            instanceId = binding.instanceId,
                        )
                    },
                )
            },
            activeContextId = activeContextId,
        )

    @Serializable
    private data class PersistedCompositionState(
        val schemaVersion: Int,
        val instances: List<PersistedModuleInstance> = emptyList(),
        val contexts: List<PersistedLauncherContext> = emptyList(),
        val activeContextId: String? = null,
    )

    @Serializable
    private data class PersistedModuleInstance(
        val id: String,
        val name: String,
        val providerId: String,
        val configurationJson: String = "{}",
    )

    @Serializable
    private data class PersistedLauncherContext(
        val id: String,
        val name: String,
        val bindings: List<PersistedContextBinding> = emptyList(),
    )

    @Serializable
    private data class PersistedContextBinding(
        val capability: String,
        val instanceId: String,
    )
}
