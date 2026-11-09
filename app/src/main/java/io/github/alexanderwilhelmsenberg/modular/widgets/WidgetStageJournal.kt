package io.github.alexanderwilhelmsenberg.modular.widgets

import android.content.Context

/** A host ID must be journaled before any external consent/configuration Activity starts. */
internal data class WidgetStage(
    val id: Int,
    val provider: String,
    val pageId: String,
    val phase: Phase,
    val createdAt: Long,
) {
    enum class Phase { BIND, CONFIGURE_NEW, RECONFIGURE }
}

internal class WidgetStageJournal(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("workspace_widget_stage_v1", Context.MODE_PRIVATE)

    fun read(): WidgetStage? {
        val id = preferences.getInt("id", -1)
        val provider = preferences.getString("provider", null)
        val pageId = preferences.getString("page", null)
        val phase = preferences.getString("phase", null)?.let { runCatching { WidgetStage.Phase.valueOf(it) }.getOrNull() }
        if (id < 0 || provider.isNullOrBlank() || pageId.isNullOrBlank() || phase == null) return null
        return WidgetStage(id, provider, pageId, phase, preferences.getLong("created", 0))
    }

    fun write(stage: WidgetStage) {
        check(preferences.edit().putInt("id", stage.id).putString("provider", stage.provider)
            .putString("page", stage.pageId).putString("phase", stage.phase.name)
            .putLong("created", stage.createdAt).commit()) { "Could not stage widget ID" }
    }

    fun clear() {
        check(preferences.edit().clear().commit()) { "Could not clear staged widget ID" }
    }
}

/** Only IDs in persisted placements or a current external flow may remain allocated. */
internal fun abandonedWidgetIds(hostIds: Collection<Int>, savedIds: Collection<Int>, stage: WidgetStage?): Set<Int> =
    hostIds.toSet() - savedIds.toSet() - listOfNotNull(stage?.id).toSet()

internal fun mayStartWidgetFlow(stage: WidgetStage?, finishingId: Int?): Boolean =
    stage == null && finishingId == null

internal fun resultMatchesStage(id: Int, stage: WidgetStage?): Boolean = id > 0 && stage?.id == id
