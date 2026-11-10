package io.github.alexanderwilhelmsenberg.modular.moduleapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/** Bounds and envelope validation for the v1 native workspace-page wire format. */
object WorkspacePageContract {
    const val VERSION = 1
    const val MAX_REQUEST_BYTES = 8_192
    const val MAX_RESPONSE_BYTES = 262_144
    const val MAX_DOCUMENT_ID_LENGTH = 512
    const val MAX_PAGE_SIZE = 100
    const val FILES_KIND = "files"
    private val writeMode = Regex("rw:[A-Za-z0-9._:-]{1,128}")

    fun validDocumentMode(mode: String): Boolean = mode == "r" || writeMode.matches(mode)

    private val json = Json { isLenient = false }

    fun parseRequest(raw: String): JsonObject = parse(raw, MAX_REQUEST_BYTES)
    fun parseResponse(raw: String): JsonObject = parse(raw, MAX_RESPONSE_BYTES)

    fun validatePageRequest(raw: String): JsonObject = parseRequest(raw).also { value ->
        value["pageSize"]?.let {
            require(it.jsonPrimitive.intOrNull?.let { size -> size in 1..MAX_PAGE_SIZE } == true) {
                "Invalid workspace page size"
            }
        }
        value["cursor"]?.takeIf { it != JsonNull }?.let {
            require(it.jsonPrimitive.contentOrNull?.length?.let { length -> length in 1..512 } == true) {
                "Invalid workspace page cursor"
            }
        }
    }

    fun validateActionRequest(raw: String): JsonObject = parseRequest(raw).also { value ->
        val actionId = value["actionId"]?.jsonPrimitive?.contentOrNull
        val token = value["operationToken"]?.jsonPrimitive?.contentOrNull
        require(actionId != null && actionId.length in 1..512) { "Invalid workspace action ID" }
        require(token != null && runCatching { UUID.fromString(token) }.isSuccess) {
            "Invalid operation token"
        }
        require(value["args"] == null || value["args"] is JsonObject) { "Invalid workspace action arguments" }
    }

    fun validateFilesPage(raw: String): JsonObject = parseResponse(raw).also { value ->
        require(value["kind"]?.jsonPrimitive?.contentOrNull == FILES_KIND) {
            "Unsupported native page kind"
        }
        for ((field, maximum) in listOf("items" to MAX_PAGE_SIZE, "actions" to 128,
            "breadcrumbs" to 64, "mapNodes" to 512)) {
            val entry = value[field] ?: continue
            require(entry is JsonArray && entry.size <= maximum) { "Invalid workspace page $field" }
        }
    }

    private fun parse(raw: String, maximum: Int): JsonObject {
        require(raw.toByteArray(Charsets.UTF_8).size <= maximum) { "Workspace page message exceeds limit" }
        val objectValue = json.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("Workspace page message must be an object")
        require(objectValue["version"]?.jsonPrimitive?.intOrNull == VERSION) {
            "Unsupported workspace page version"
        }
        return objectValue
    }
}
