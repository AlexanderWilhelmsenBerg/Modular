package io.github.alexanderwilhelmsenberg.modular.mcp

import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import java.security.MessageDigest
import kotlinx.serialization.json.*

data class ModuleToolCandidate(
    val approvalKey: String,
    val instanceId: String,
    val providerId: String,
    val name: String,
    val description: String,
    val schema: JsonObject,
    val requiredCapabilities: Set<CapabilityId>,
    val identityEvidence: String,
)

object McpModulePolicy {
    fun descriptors(raw: String, identityAndVersion: String, instanceId: String, providerId: String): List<ModuleToolCandidate> {
        require(raw.length <= 32_768) { "Module tool catalog is too large" }
        val tools = Json.parseToJsonElement(raw).jsonArray
        require(tools.size <= 16)
        return tools.map { value ->
            val tool = value.jsonObject
            val name = tool.getValue("name").jsonPrimitive.content
            require(name.matches(Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")))
            require(tool["readOnly"]?.jsonPrimitive?.booleanOrNull == true) { "Only reviewed read-only module exports are supported" }
            val description = tool["description"]?.jsonPrimitive?.content.orEmpty()
            require(description.length <= 512)
            val schema = tool.getValue("inputSchema").jsonObject
            require(schema["type"]?.jsonPrimitive?.content == "object")
            require(schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false)
            val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            require(properties.size <= 16)
            properties.forEach { (key, rule) ->
                require(key.matches(Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")))
                require(rule.jsonObject["type"]?.jsonPrimitive?.content in setOf("string", "integer", "number", "boolean"))
            }
            val required = schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
            require(required.all { it in properties })
            val capabilities = tool["requiredCapabilities"]?.jsonArray.orEmpty().map {
                requireNotNull(CapabilityId.parse(it.jsonPrimitive.content))
            }.toSet()
            val fingerprint = digest("$identityAndVersion|$instanceId|$providerId|" + tool.toString())
            ModuleToolCandidate(fingerprint, instanceId, providerId, name, description, schema, capabilities, identityAndVersion)
        }.also { require(it.map { tool -> tool.name }.toSet().size == it.size) }
    }

    fun validateArguments(tool: ModuleToolCandidate, arguments: JsonObject) {
        require(arguments.toString().length <= 8192)
        val properties = tool.schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        require(arguments.keys.all { it in properties }) { "Unknown module tool argument" }
        tool.schema["required"]?.jsonArray.orEmpty().forEach { require(it.jsonPrimitive.content in arguments) }
        arguments.forEach { (name, value) ->
            val primitive = value as? JsonPrimitive ?: error("Module arguments must be primitive values")
            require(primitive != JsonNull)
            val type = properties.getValue(name).jsonObject.getValue("type").jsonPrimitive.content
            require(when (type) {
                "string" -> primitive.isString && primitive.content.length <= 1024
                "integer" -> !primitive.isString && primitive.longOrNull != null
                "number" -> !primitive.isString && primitive.doubleOrNull?.isFinite() == true
                "boolean" -> !primitive.isString && primitive.booleanOrNull != null
                else -> false
            }) { "Invalid module tool argument: $name" }
        }
    }

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

data class BrokerPolicy(
    val allowContextChanges: Boolean = false,
    val allowAppLaunch: Boolean = false,
    val approvedModuleTools: Set<String> = emptySet(),
) {
    fun allows(tool: String): Boolean = when (tool) {
        "launcher.context.current", "launcher.context.list", "launcher.modules.list",
        "launcher.apps.search", "launcher.module.tools" -> true
        "launcher.context.activate" -> allowContextChanges
        "launcher.apps.launch" -> allowAppLaunch
        "launcher.module.invoke" -> approvedModuleTools.isNotEmpty()
        else -> false
    }
}
