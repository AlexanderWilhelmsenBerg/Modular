package io.github.alexanderwilhelmsenberg.modular.moduleapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Validated, immutable declaration of a protocol v4 provider. */
data class ModuleDescriptor @JvmOverloads constructor(
    val schemaVersion: Int,
    val moduleId: String,
    val moduleVersion: String,
    val displayName: String,
    val description: String,
    val coreProtocolMin: Int,
    val coreProtocolMax: Int,
    val providedCapabilities: List<String>,
    val requestedCoreCapabilities: List<String>,
    val managementActivity: String?,
    val configurationFields: List<InstanceConfigurationField>,
    val workspaceActivity: String? = null,
    /** Lowest Android API on which this add-on can embed its workspace surface. */
    val workspaceSurfaceMinSdk: Int = 30,
)

/** A flat, bounded setting. Every field has a default value. */
data class InstanceConfigurationField(
    val key: String,
    val title: String,
    val type: String,
    val defaultValue: JsonPrimitive,
    val enumValues: List<JsonPrimitive>,
    val min: Long?,
    val max: Long?,
    val minLength: Int?,
    val maxLength: Int?,
)

/**
 * Pure JSON validation shared by Core and module SDK. Exceptions use fixed
 * messages; untrusted descriptor/configuration text is never echoed.
 */
object ModuleDescriptorCodec {
    const val MAX_DESCRIPTOR_BYTES = 32_768
    const val MAX_CONFIGURATION_BYTES = 8_192
    const val MAX_CONFIGURATION_FIELDS = 32

    private val json = Json { isLenient = false }
    private val moduleIdPattern = Regex("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+")
    private val fieldKeyPattern = Regex("[a-z][a-zA-Z0-9_]{0,63}")
    private val activityPattern = Regex("(?:\\.[A-Za-z_][A-Za-z0-9_$.]*|[A-Za-z_][A-Za-z0-9_$.]*\\.[A-Za-z_][A-Za-z0-9_$]*)")
    private val integerPattern = Regex("(?:0|-[1-9][0-9]*|[1-9][0-9]*)")

    @JvmStatic
    fun parse(rawJson: String): ModuleDescriptor {
        require(rawJson.toByteArray(Charsets.UTF_8).size <= MAX_DESCRIPTOR_BYTES) {
            "Descriptor exceeds size limit"
        }
        val root = parseObject(rawJson, "Invalid descriptor JSON")
        exactKeys(root, setOf("schemaVersion", "moduleId", "moduleVersion", "displayName", "description", "coreProtocol", "providedCapabilities", "requestedCoreCapabilities", "managementActivity", "workspaceActivity", "workspaceSurfaceMinSdk", "instanceConfiguration"), "Invalid descriptor fields")
        require(int(root, "schemaVersion") == 1) { "Unsupported descriptor schema version" }
        val moduleId = string(root, "moduleId", 160)
        require(moduleIdPattern.matches(moduleId)) { "Invalid module ID" }
        val moduleVersion = string(root, "moduleVersion", 64)
        val displayName = string(root, "displayName", 120)
        val description = string(root, "description", 1_024, allowEmpty = true)
        val protocol = root["coreProtocol"] as? JsonObject
            ?: throw IllegalArgumentException("Invalid Core protocol range")
        exactKeys(protocol, setOf("min", "max"), "Invalid Core protocol range")
        val min = int(protocol, "min")
        val max = int(protocol, "max")
        require(min in 1..1_024 && max in min..1_024) { "Invalid Core protocol range" }
        val provided = capabilities(root, "providedCapabilities", allowEmpty = false)
        val requested = capabilities(root, "requestedCoreCapabilities", allowEmpty = true)
        val activity = root["managementActivity"]?.let {
            val value = (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.contentOrNull
                ?: throw IllegalArgumentException("Invalid management Activity")
            require(value.length <= 200 && activityPattern.matches(value)) {
                "Invalid management Activity"
            }
            value
        }
        val workspaceActivity = root["workspaceActivity"]?.let {
            val value = (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.contentOrNull
                ?: throw IllegalArgumentException("Invalid workspace Activity")
            require(value.length <= 200 && activityPattern.matches(value)) {
                "Invalid workspace Activity"
            }
            value
        }
        val workspaceSurfaceMinSdk = optionalInt(root, "workspaceSurfaceMinSdk") ?: 30
        require(workspaceSurfaceMinSdk in 30..10_000) { "Invalid workspace surface minimum SDK" }
        val schema = root["instanceConfiguration"] as? JsonObject
            ?: throw IllegalArgumentException("Invalid instance configuration schema")
        exactKeys(schema, setOf("schemaVersion", "fields"), "Invalid instance configuration schema")
        require(int(schema, "schemaVersion") == 1) { "Unsupported configuration schema version" }
        val fieldsArray = schema["fields"] as? JsonArray
            ?: throw IllegalArgumentException("Invalid instance configuration schema")
        require(fieldsArray.size <= MAX_CONFIGURATION_FIELDS) { "Too many configuration fields" }
        val fields = fieldsArray.map(::parseField)
        require(fields.map { it.key }.distinct().size == fields.size) { "Duplicate configuration key" }
        val descriptor = ModuleDescriptor(1, moduleId, moduleVersion, displayName, description, min, max,
            provided, requested, activity, fields, workspaceActivity, workspaceSurfaceMinSdk)
        normalizeConfiguration(descriptor, "{}")
        return descriptor
    }

    /** Encodes a descriptor with the same validation used for packaged JSON. */
    @JvmStatic
    fun encode(descriptor: ModuleDescriptor): String {
        val root = linkedMapOf<String, JsonElement>(
            "schemaVersion" to JsonPrimitive(descriptor.schemaVersion),
            "moduleId" to JsonPrimitive(descriptor.moduleId),
            "moduleVersion" to JsonPrimitive(descriptor.moduleVersion),
            "displayName" to JsonPrimitive(descriptor.displayName),
            "description" to JsonPrimitive(descriptor.description),
            "coreProtocol" to JsonObject(mapOf(
                "min" to JsonPrimitive(descriptor.coreProtocolMin),
                "max" to JsonPrimitive(descriptor.coreProtocolMax))),
            "providedCapabilities" to JsonArray(descriptor.providedCapabilities.map { JsonPrimitive(it) }),
            "requestedCoreCapabilities" to JsonArray(descriptor.requestedCoreCapabilities.map { JsonPrimitive(it) }),
        )
        descriptor.managementActivity?.let { root["managementActivity"] = JsonPrimitive(it) }
        descriptor.workspaceActivity?.let { root["workspaceActivity"] = JsonPrimitive(it) }
        root["workspaceSurfaceMinSdk"] = JsonPrimitive(descriptor.workspaceSurfaceMinSdk)
        root["instanceConfiguration"] = JsonObject(mapOf(
            "schemaVersion" to JsonPrimitive(1),
            "fields" to JsonArray(descriptor.configurationFields.map { field ->
                val entry = linkedMapOf<String, JsonElement>(
                    "key" to JsonPrimitive(field.key),
                    "title" to JsonPrimitive(field.title),
                    "type" to JsonPrimitive(field.type),
                    "default" to field.defaultValue,
                )
                if (field.enumValues.isNotEmpty()) entry["enum"] = JsonArray(field.enumValues)
                field.min?.let { entry["min"] = JsonPrimitive(it) }
                field.max?.let { entry["max"] = JsonPrimitive(it) }
                field.minLength?.let { entry["minLength"] = JsonPrimitive(it) }
                field.maxLength?.let { entry["maxLength"] = JsonPrimitive(it) }
                JsonObject(entry)
            })))
        val encoded = JsonObject(root).toString()
        parse(encoded)
        return encoded
    }

    /** Validates input and returns deterministic JSON with all defaults applied. */
    @JvmStatic
    fun normalizeConfiguration(descriptor: ModuleDescriptor, rawJson: String): String {
        require(rawJson.toByteArray(Charsets.UTF_8).size <= MAX_CONFIGURATION_BYTES) {
            "Configuration exceeds size limit"
        }
        val supplied = parseObject(rawJson, "Invalid configuration JSON")
        val fields = descriptor.configurationFields.associateBy { it.key }
        require(supplied.keys.all(fields::containsKey)) { "Unknown configuration key" }
        val normalized = sortedMapOf<String, JsonElement>()
        for (field in descriptor.configurationFields) {
            val value = supplied[field.key] ?: field.defaultValue
            require(value is JsonPrimitive && validValue(field, value)) {
                "Invalid configuration value"
            }
            normalized[field.key] = when (field.type) {
                "integer" -> JsonPrimitive(value.content.toLong())
                "boolean" -> JsonPrimitive(value.booleanOrNull!!)
                else -> JsonPrimitive(value.content)
            }
        }
        val result = JsonObject(normalized).toString()
        require(result.toByteArray(Charsets.UTF_8).size <= MAX_CONFIGURATION_BYTES) {
            "Configuration exceeds size limit"
        }
        return result
    }

    @JvmStatic
    fun normalizeConfiguration(descriptorJson: String, rawJson: String): String =
        normalizeConfiguration(parse(descriptorJson), rawJson)

    @JvmStatic
    fun validateConfiguration(descriptor: ModuleDescriptor, rawJson: String) {
        normalizeConfiguration(descriptor, rawJson)
    }

    private fun parseField(element: JsonElement): InstanceConfigurationField {
        val obj = element as? JsonObject ?: throw IllegalArgumentException("Invalid configuration field")
        exactKeys(obj, setOf("key", "title", "type", "default", "enum", "min", "max", "minLength", "maxLength"), "Invalid configuration field")
        val key = string(obj, "key", 64)
        require(fieldKeyPattern.matches(key)) { "Invalid configuration key" }
        val title = string(obj, "title", 120)
        val type = string(obj, "type", 16)
        require(type in setOf("boolean", "string", "integer")) { "Invalid configuration type" }
        val default = obj["default"] as? JsonPrimitive
            ?: throw IllegalArgumentException("Invalid configuration default")
        val enumValues = obj["enum"]?.let {
            (it as? JsonArray)?.map { entry -> entry as? JsonPrimitive ?: throw IllegalArgumentException("Invalid configuration enum") }
                ?: throw IllegalArgumentException("Invalid configuration enum")
        } ?: emptyList()
        require(enumValues.size <= 32 && enumValues.distinct().size == enumValues.size) {
            "Invalid configuration enum"
        }
        require(type != "boolean" || enumValues.isEmpty()) { "Invalid configuration enum" }
        val min = optionalLong(obj, "min")
        val max = optionalLong(obj, "max")
        val minLength = optionalInt(obj, "minLength")
        val maxLength = optionalInt(obj, "maxLength")
        require((type == "integer" || (min == null && max == null)) &&
            (type == "string" || (minLength == null && maxLength == null))) {
            "Invalid configuration bounds"
        }
        require((min == null || min in -1_000_000_000L..1_000_000_000L) &&
            (max == null || max in -1_000_000_000L..1_000_000_000L) &&
            (min == null || max == null || min <= max) &&
            (minLength == null || minLength in 0..256) &&
            (maxLength == null || maxLength in 0..256) &&
            (minLength == null || maxLength == null || minLength <= maxLength)) {
            "Invalid configuration bounds"
        }
        val field = InstanceConfigurationField(key, title, type, default, enumValues, min, max, minLength, maxLength)
        require(validValue(field, default) &&
            enumValues.all { validValue(field.copy(enumValues = emptyList()), it) }) {
            "Invalid configuration default or enum"
        }
        return field
    }

    private fun validValue(field: InstanceConfigurationField, value: JsonPrimitive): Boolean {
        if (!validType(field, value)) return false
        if (field.enumValues.isNotEmpty() && value !in field.enumValues) return false
        return when (field.type) {
            "string" -> value.content.length in (field.minLength ?: 0)..(field.maxLength ?: 256)
            "integer" -> {
                val number = value.content.toLongOrNull() ?: return false
                number in (field.min ?: -1_000_000_000L)..(field.max ?: 1_000_000_000L)
            }
            else -> true
        }
    }

    private fun validType(field: InstanceConfigurationField, value: JsonPrimitive): Boolean =
        when (field.type) {
            "string" -> value.isString
            "boolean" -> !value.isString && value.booleanOrNull != null
            "integer" -> !value.isString && integerPattern.matches(value.content) && value.content.toLongOrNull() != null
            else -> false
        }

    private fun capabilities(root: JsonObject, key: String, allowEmpty: Boolean): List<String> {
        val array = root[key] as? JsonArray ?: throw IllegalArgumentException("Invalid capability declaration")
        require(array.size <= 32 && (allowEmpty || array.isNotEmpty())) { "Invalid capability declaration" }
        val values = array.map {
            val value = (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.contentOrNull
                ?: throw IllegalArgumentException("Invalid capability declaration")
            require(value.length <= 128 && CapabilityId.parse(value) != null) { "Invalid capability declaration" }
            value
        }
        require(values.distinct().size == values.size) { "Duplicate capability declaration" }
        return values
    }

    private fun parseObject(raw: String, error: String): JsonObject =
        try {
            json.parseToJsonElement(raw) as? JsonObject ?: throw IllegalArgumentException(error)
        } catch (_: Exception) {
            throw IllegalArgumentException(error)
        }

    private fun exactKeys(obj: JsonObject, keys: Set<String>, error: String) {
        require(obj.keys.all { it in keys }) { error }
    }

    private fun string(obj: JsonObject, key: String, max: Int, allowEmpty: Boolean = false): String {
        val value = (obj[key] as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.contentOrNull
            ?: throw IllegalArgumentException("Invalid descriptor string")
        require(value.length <= max && (allowEmpty || value.isNotBlank())) { "Invalid descriptor string" }
        return value
    }

    private fun int(obj: JsonObject, key: String): Int =
        (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: throw IllegalArgumentException("Invalid descriptor integer")

    private fun optionalInt(obj: JsonObject, key: String): Int? =
        if (key in obj) int(obj, key) else null

    private fun optionalLong(obj: JsonObject, key: String): Long? =
        if (key !in obj) null else {
            val value = obj[key] as? JsonPrimitive
                ?: throw IllegalArgumentException("Invalid configuration bound")
            require(!value.isString && integerPattern.matches(value.content)) { "Invalid configuration bound" }
            value.content.toLongOrNull() ?: throw IllegalArgumentException("Invalid configuration bound")
        }
}
