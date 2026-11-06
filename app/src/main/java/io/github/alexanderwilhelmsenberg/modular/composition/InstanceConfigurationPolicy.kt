package io.github.alexanderwilhelmsenberg.modular.composition

import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleDescriptorCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Applies the live provider contract without altering saved data for an absent provider. */
internal fun ModuleInstance.withValidatedConfiguration(
    routes: List<CapabilityRoute>,
): ModuleInstance {
    CompositionStateCodec.validateConfigurationJson(configurationJson)
    val matching = routes.filter { it.providerId == providerId }
    if (matching.isEmpty()) return this

    val descriptors = matching.map { it.descriptor }.distinct()
    require(descriptors.size == 1) {
        "Provider has conflicting configuration descriptors"
    }
    val descriptor = descriptors.single()
    if (descriptor == null) {
        val objectValue = Json.parseToJsonElement(configurationJson) as JsonObject
        require(objectValue.isEmpty()) {
            "This provider does not support instance configuration"
        }
        return copy(configurationJson = "{}")
    }

    return copy(
        configurationJson = ModuleDescriptorCodec.normalizeConfiguration(
            descriptor,
            configurationJson,
        ),
    )
}
