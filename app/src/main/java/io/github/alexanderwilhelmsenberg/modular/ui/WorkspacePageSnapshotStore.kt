package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.Context
import io.github.alexanderwilhelmsenberg.modular.moduleapi.WorkspacePageContract
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A display-only copy of an ordinary Files list. No command or document state is persisted. */
internal object WorkspacePageSnapshot {
    const val SCHEMA = 1
    private val passiveViews = setOf("overview", "browser", "search", "trash")
    private val pageFields = setOf("version", "kind", "view", "title", "summary", "state", "layout",
        "locationId", "folderId", "scrollKey", "scrollAnchor", "scrollOffset")
    private val itemFields = setOf("id", "name", "kind", "mimeType", "subtitle", "path",
        "sizeBytes", "modifiedAt", "createdAt")
    private val crumbFields = setOf("name")
    private val appearanceFields = setOf("id", "name", "icon", "color", "customIcon", "customColor")

    private fun appearance(value: kotlinx.serialization.json.JsonElement?): JsonObject? =
        (value as? JsonObject)?.let { JsonObject(it.filter { (field, entry) ->
            field in appearanceFields && entry is JsonPrimitive }) }

    fun sanitize(raw: String): String? = runCatching {
        val source = WorkspacePageContract.validateFilesPage(raw)
        val view = (source["view"] as? JsonPrimitive)?.content ?: return null
        if (view !in passiveViews) return null
        if ((source["state"] as? JsonPrimitive)?.content !in setOf(null, "ready")) return null
        val result = buildJsonObject {
            source.forEach { (name, value) ->
                if (name in pageFields && value is JsonPrimitive) put(name, value)
            }
            appearance(source["currentFolder"])?.let { put("currentFolder", it) }
            put("items", JsonArray((source["items"] as? JsonArray).orEmpty().mapNotNull { item ->
                (item as? JsonObject)?.let { entry ->
                    JsonObject(entry.filter { (field, value) -> field in itemFields && value is JsonPrimitive } +
                        listOfNotNull(appearance(entry["folderAppearance"])?.let { "folderAppearance" to it }).toMap())
                }
            }))
            put("breadcrumbs", JsonArray((source["breadcrumbs"] as? JsonArray).orEmpty().mapNotNull { crumb ->
                (crumb as? JsonObject)?.let { entry ->
                    JsonObject(entry.filter { (field, value) -> field in crumbFields && value is JsonPrimitive })
                }
            }))
        }.toString()
        result.takeIf { it.toByteArray(Charsets.UTF_8).size <= 128 * 1024 }
    }.getOrNull()

    fun parse(raw: String?): String? = raw?.takeIf {
        it.toByteArray(Charsets.UTF_8).size <= 128 * 1024 && sanitize(it) == it
    }

    fun mustClear(raw: String): Boolean = runCatching {
        val page = WorkspacePageContract.validateFilesPage(raw)
        (page["state"] as? JsonPrimitive)?.content in setOf("permission", "unavailable", "error")
    }.getOrDefault(false)
}

/** App-private and keyed to the placed component, signing identity, and cache schema. */
internal class WorkspacePageSnapshotStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("workspace_page_snapshots", Context.MODE_PRIVATE)

    private fun key(component: String, signer: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${WorkspacePageSnapshot.SCHEMA}|$component|$signer".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @Synchronized fun save(component: String, signer: String, raw: String) {
        val safe = WorkspacePageSnapshot.sanitize(raw) ?: return
        purgeOtherIdentities(component, signer)
        val cacheKey = key(component, signer)
        if (preferences.getString("page:$cacheKey", null) != safe) {
            preferences.edit().putString("page:$cacheKey", safe)
                .putString("owner:$cacheKey", component).commit()
        }
    }

    @Synchronized fun restore(component: String, signer: String): String? {
        purgeOtherIdentities(component, signer)
        val cacheKey = key(component, signer)
        val raw = preferences.getString("page:$cacheKey", null)
        return WorkspacePageSnapshot.parse(raw).also {
            if (it == null && raw != null) preferences.edit().remove("page:$cacheKey")
                .remove("owner:$cacheKey").commit()
        }
    }

    @Synchronized fun purge(component: String) {
        val matching = preferences.all.keys.filter { it.startsWith("owner:") &&
            preferences.getString(it, null) == component }.map { it.removePrefix("owner:") }
        if (matching.isNotEmpty()) preferences.edit().apply {
            matching.forEach { remove("owner:$it"); remove("page:$it") }
        }.commit()
    }

    @Synchronized private fun purgeOtherIdentities(component: String, signer: String) {
        val keep = key(component, signer)
        val stale = preferences.all.keys.filter { it.startsWith("owner:") &&
            it != "owner:$keep" && preferences.getString(it, null) == component }
            .map { it.removePrefix("owner:") }
        if (stale.isNotEmpty()) preferences.edit().apply {
            stale.forEach { remove("owner:$it"); remove("page:$it") }
        }.commit()
    }
}
