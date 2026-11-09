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
    /** Horizontal grid span: one half-width cell or both cells. */
    val span: Int = 2,
    /** Space inside this widget's allocated cell. */
    val paddingDp: Int = 0,
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
)

data class ListeningPreferences(
    val enabled: Boolean = false,
    val kind: HeadsetKind = HeadsetKind.ANY,
    val apps: List<AppKey> = emptyList(),
)
