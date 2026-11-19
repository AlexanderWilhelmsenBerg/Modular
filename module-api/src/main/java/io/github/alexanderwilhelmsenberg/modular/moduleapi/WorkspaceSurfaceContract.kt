package io.github.alexanderwilhelmsenberg.modular.moduleapi

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

data class WorkspaceSurfaceHostSpec(
    val sessionId: String,
    val widthPx: Int,
    val heightPx: Int,
    val displayId: Int,
    val densityDpi: Int,
    val fontScale: Float,
    val visible: Boolean,
    val theme: String,
    val colors: Map<String, Int>,
    val localeTags: String,
)

data class WorkspaceSurfaceState(
    val canGoBack: Boolean,
    val exclusiveGestures: Boolean = true,
)

/** Shared, bounded transport validation for independently rendered workspace UI. */
object WorkspaceSurfaceContract {
    const val VERSION = 1
    const val MAX_MESSAGE_BYTES = 8_192
    const val MAX_DIMENSION = 8_192
    const val MAX_AREA = 16_777_216
    private val json = Json { isLenient = false }
    private val noncePattern = Regex("[A-Za-z0-9_-]{32,128}")
    private val destinations = setOf("home", "apps", "previous", "next", "settings")

    fun parseHostSpec(raw: String): WorkspaceSurfaceHostSpec {
        val obj = parse(raw)
        require(int(obj, "version") == VERSION) { "Unsupported workspace surface version" }
        val sessionId = string(obj, "sessionId", 36)
        require(runCatching { UUID.fromString(sessionId).toString() == sessionId.lowercase() }.getOrDefault(false)) {
            "Invalid workspace surface session ID"
        }
        val width = int(obj, "widthPx")
        val height = int(obj, "heightPx")
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION && width.toLong() * height <= MAX_AREA) {
            "Invalid workspace surface dimensions"
        }
        val display = int(obj, "displayId")
        val density = int(obj, "densityDpi")
        require(display >= 0 && density in 72..960) { "Invalid workspace surface display" }
        val scale = number(obj, "fontScale")
        require(scale.isFinite() && scale in 0.5..3.0) { "Invalid workspace surface font scale" }
        val visible = boolean(obj, "visible")
        val theme = string(obj, "theme", 16)
        require(theme in setOf("light", "dark", "amoled")) { "Invalid workspace surface theme" }
        val colorsObj = obj["colors"] as? JsonObject ?: throw IllegalArgumentException("Invalid workspace surface colors")
        require(colorsObj.size <= 32) { "Too many workspace surface colors" }
        val colors = colorsObj.mapValues { (name, color) ->
            require(name.matches(Regex("[A-Za-z][A-Za-z0-9]{0,31}"))) { "Invalid workspace surface color name" }
            (color as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: throw IllegalArgumentException("Invalid workspace surface color")
        }
        val localeTags = string(obj, "localeTags", 256, allowEmpty = true)
        return WorkspaceSurfaceHostSpec(sessionId, width, height, display, density, scale.toFloat(),
            visible, theme, colors, localeTags)
    }

    fun parseState(raw: String): WorkspaceSurfaceState {
        val obj = parse(raw)
        require(int(obj, "version") == VERSION) { "Unsupported workspace surface state version" }
        return WorkspaceSurfaceState(boolean(obj, "canGoBack"),
            if ("exclusiveGestures" in obj) boolean(obj, "exclusiveGestures") else true)
    }

    fun encodeState(state: WorkspaceSurfaceState): String =
        "{\"version\":1,\"canGoBack\":${state.canGoBack},\"exclusiveGestures\":${state.exclusiveGestures}}"

    fun validateNavigationDestination(destination: String) {
        require(destination in destinations) { "Invalid workspace surface destination" }
    }

    fun validateActivityNonce(nonce: String) {
        require(noncePattern.matches(nonce)) { "Invalid workspace surface nonce" }
    }

    private fun parse(raw: String): JsonObject {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) { "Workspace surface message exceeds limit" }
        return try { json.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("Workspace surface message must be an object")
        } catch (_: Exception) { throw IllegalArgumentException("Invalid workspace surface JSON") }
    }

    private fun int(obj: JsonObject, key: String): Int =
        (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: throw IllegalArgumentException("Invalid workspace surface integer")

    private fun number(obj: JsonObject, key: String): Double =
        (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            ?: throw IllegalArgumentException("Invalid workspace surface number")

    private fun boolean(obj: JsonObject, key: String): Boolean =
        (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw IllegalArgumentException("Invalid workspace surface boolean")

    private fun string(obj: JsonObject, key: String, maxLength: Int, allowEmpty: Boolean = false): String {
        val value = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: throw IllegalArgumentException("Invalid workspace surface string")
        require(value.length <= maxLength && (allowEmpty || value.isNotEmpty())) { "Invalid workspace surface string" }
        return value
    }
}
