package io.github.alexanderwilhelmsenberg.modular.widgets

import io.github.alexanderwilhelmsenberg.modular.workspace.*

/** Preferred provider cells determine initial size; resize minimums must not collapse it. */
internal fun initialWidgetGridSpan(
    preferences: WorkspacePreferences,
    geometry: WidgetAreaGeometry,
    targetColumns: Int,
    targetRows: Int,
    minWidthDp: Int,
    minHeightDp: Int,
): WidgetGridSpan {
    val columns = preferences.gridColumnCount
    val widthSpan = if (targetColumns > 0) targetColumns.coerceIn(1, columns) else
        (1..columns).firstOrNull { gridSpanWidthDp(it, geometry, preferences) >= minWidthDp } ?: columns
    val heightSpan = if (targetRows > 0) targetRows.coerceIn(1, 32) else
        (1..32).firstOrNull { gridSpanHeightDp(it, preferences) >= minHeightDp } ?: 32
    return resolveWidgetGridSpan(
        WorkspaceWidget(0, "home", "", minHeightDp, columnSpan = widthSpan, rowSpan = heightSpan),
        preferences, geometry, minWidthDp, minHeightDp,
    )
}
