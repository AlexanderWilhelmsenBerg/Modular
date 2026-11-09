package io.github.alexanderwilhelmsenberg.modular.workspace

import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.organization.HeadsetKind

/** Stable storage keys for pages that can contain Android widgets. */
object WorkspacePageId {
    const val HOME = "home"
    const val WIDGET_PREFIX = "widget:"

    fun widget(id: String): String = "$WIDGET_PREFIX$id"
}

data class WorkspacePage(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
)

/** List position is the widget's order within its page. */
data class WorkspaceWidget(
    val appWidgetId: Int,
    val pageId: String,
    /** Flattened Android ComponentName of the provider. */
    val provider: String,
    val heightDp: Int,
    /** Identity of this device-local AppWidgetHost; empty for older placements. */
    val hostToken: String = "",
    /** Legacy two-column span, retained for older saved placements. */
    val span: Int = 2,
    /** Space inside this widget's allocated cell. */
    val paddingDp: Int = 0,
    /** Grid size; zero resolves the legacy span or height when loading an older placement. */
    val columnSpan: Int = 0,
    val rowSpan: Int = 0,
    /** Grid origin; -1 requests the next available slot. */
    val gridColumn: Int = -1,
    val gridRow: Int = -1,
)

enum class WorkspaceSection { CLOCK, WIDGETS, MOST_USED, LISTENING }

data class WorkspacePreferences(
    val showClock: Boolean = true,
    val showDate: Boolean = true,
    val showBattery: Boolean = false,
    val sectionOrder: List<WorkspaceSection> = WorkspaceSection.entries.toList(),
    val widgetAreaWidthPercent: Int = 100,
    val widgetMarginDp: Int = 16,
    val widgetGapDp: Int = 12,
    val gridColumnCount: Int = 4,
    val gridRowHeightDp: Int = 96,
)

data class ListeningPreferences(
    val enabled: Boolean = false,
    val kind: HeadsetKind = HeadsetKind.ANY,
    val apps: List<AppKey> = emptyList(),
)
