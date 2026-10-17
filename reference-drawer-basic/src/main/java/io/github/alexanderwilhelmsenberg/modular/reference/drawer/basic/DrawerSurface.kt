package io.github.alexanderwilhelmsenberg.modular.reference.drawer.basic

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceDocumentParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceNodeParcel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** State belongs to a single configured instance in this module process. */
class DrawerSurface {
    private var page = 0
    private var revision = 0L
    private var actions: Map<String, AppKeyParcel> = emptyMap()
    private var pageCount = 1

    @Synchronized
    fun status(): String = "{\"ready\":true,\"page\":$page,\"pages\":$pageCount,\"revision\":$revision}"

    @Synchronized
    fun render(configurationJson: String, orderedApps: List<AppSortItemParcel>): SurfaceDocumentParcel {
        require(configurationJson.length <= 8192) { "Drawer configuration is too large" }
        val config = Json.parseToJsonElement(configurationJson).jsonObject
        val profileValue = config["profileSerial"]
        val profile = profileValue?.jsonPrimitive?.longOrNull
        require(profileValue == null || profile != null) { "Invalid profileSerial" }
        val apps = orderedApps.filter { profile == null || it.key.profileSerial == profile }
        pageCount = maxOf(1, (apps.size + PAGE_SIZE - 1) / PAGE_SIZE)
        page = page.coerceIn(0, pageCount - 1)
        revision++
        val visible = apps.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        actions = visible.mapIndexed { index, app -> "launch.$revision.$index" to app.key }.toMap()
        val children = mutableListOf(
            text("title", "Apps"),
            text("page", "Page ${page + 1} of $pageCount · ${apps.size} apps"),
        )
        visible.forEachIndexed { index, app ->
            children += button("app.$index", app.label.take(160).ifBlank { app.key.packageName.take(160) },
                "launch.$revision.$index")
        }
        if (apps.isEmpty()) children += text("empty", "No apps in this profile.")
        if (page > 0) children += button("previous", "Previous", "previous.$revision")
        if (page + 1 < pageCount) children += button("next", "Next", "next.$revision")
        return SurfaceDocumentParcel(
            SurfaceDocumentParcel.CURRENT_SCHEMA_VERSION, revision,
            SurfaceNodeParcel("drawer.root", SurfaceNodeParcel.TYPE_COLUMN, children = children),
        )
    }

    /** Actions must belong to the latest validated render of this instance. */
    @Synchronized
    fun action(actionId: String, launch: (AppKeyParcel) -> Unit): Boolean {
        when (actionId) {
            "previous.$revision" -> if (page > 0) { page--; revision++; actions = emptyMap(); return true }
            "next.$revision" -> if (page + 1 < pageCount) { page++; revision++; actions = emptyMap(); return true }
            else -> {
                val key = actions[actionId] ?: return false
                launch(key)
                return true
            }
        }
        return false
    }

    private fun text(id: String, label: String) =
        SurfaceNodeParcel("drawer.$id", SurfaceNodeParcel.TYPE_TEXT, text = label)
    private fun button(id: String, label: String, action: String) =
        SurfaceNodeParcel("drawer.$id", SurfaceNodeParcel.TYPE_BUTTON, text = label, actionId = action)

    companion object { const val PAGE_SIZE = 30 }
}
