package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Core stores only a hashed folder/view key and its last scroll coordinates. */
internal data class WorkspacePageScroll(val key: String, val anchor: Int, val offset: Int) {
    fun valid(): Boolean = key.matches(Regex("[0-9a-f]{64}")) &&
        anchor in 0..100 && offset in 0..100_000

    fun json(): JsonObject = buildJsonObject {
        put("key", key)
        put("anchor", anchor)
        put("offset", offset)
    }

    companion object {
        fun parse(raw: String?): WorkspacePageScroll? = runCatching {
            val objectValue = Json.parseToJsonElement(raw ?: return null) as? JsonObject ?: return null
            WorkspacePageScroll(
                objectValue["key"]?.jsonPrimitive?.content ?: return null,
                objectValue["anchor"]?.jsonPrimitive?.intOrNull ?: return null,
                objectValue["offset"]?.jsonPrimitive?.intOrNull ?: return null,
            ).takeIf(WorkspacePageScroll::valid)
        }.getOrNull()
    }
}

/** In-memory updates are cheap during scrolling; leaving Home commits the latest position. */
internal class WorkspacePageScrollStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("workspace_page_scroll", Context.MODE_PRIVATE)
    private val pending = mutableMapOf<String, WorkspacePageScroll>()

    @Synchronized fun record(component: String, key: String, anchor: Int, offset: Int) {
        WorkspacePageScroll(key, anchor, offset).takeIf { it.valid() }?.let { pending[component] = it }
    }

    @Synchronized fun restore(component: String): WorkspacePageScroll? =
        pending[component] ?: WorkspacePageScroll.parse(preferences.getString(component, null))

    @Synchronized fun flush(component: String) {
        val value = pending[component] ?: return
        if (preferences.edit().putString(component, value.json().toString()).commit()) {
            pending.remove(component)
        }
    }
}
