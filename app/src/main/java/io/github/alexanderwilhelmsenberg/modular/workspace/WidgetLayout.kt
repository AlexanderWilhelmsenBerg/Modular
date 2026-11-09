package io.github.alexanderwilhelmsenberg.modular.workspace

/** Reserved for the alphabet rail: a 48 dp touch lane and an 8 dp visual offset. */
const val WIDGET_RAIL_INSET_DP = 56

/** Horizontal widget bounds, measured from the start of the screen in dp. */
data class WidgetAreaGeometry(
    val leftDp: Int,
    val widthDp: Int,
    val gapDp: Int,
    /** Minimum width of either cell when a row contains two widgets. */
    val halfCellWidthDp: Int,
) {
    val rightDp: Int get() = leftDp + widthDp
}

/**
 * The rail is excluded first. Margin is applied at both edges of the remaining space,
 * then the selected percentage is centered within it. A narrow screen reduces the
 * effective margin and gap so all returned dimensions stay nonnegative.
 */
fun calculateWidgetAreaGeometry(
    screenWidthDp: Int,
    preferences: WorkspacePreferences,
): WidgetAreaGeometry {
    val availableDp = (screenWidthDp.coerceAtLeast(0) - WIDGET_RAIL_INSET_DP).coerceAtLeast(0)
    val marginDp = preferences.widgetMarginDp.coerceIn(0, 48)
        .coerceAtMost((availableDp - 2).coerceAtLeast(0) / 2)
    val contentWidthDp = availableDp - 2 * marginDp
    val widthPercent = preferences.widgetAreaWidthPercent.coerceIn(50, 100)
    val widthDp = (contentWidthDp.toLong() * widthPercent / 100).toInt()
    val leftDp = marginDp + (contentWidthDp - widthDp) / 2
    // Both cells have positive width whenever the entire area has at least two dp.
    val gapDp = preferences.widgetGapDp.coerceIn(0, 48)
        .coerceAtMost((widthDp - 2).coerceAtLeast(0))
    return WidgetAreaGeometry(leftDp, widthDp, gapDp, (widthDp - gapDp) / 2)
}

/** One widget row, preserving the input order. A lone fitting half widget keeps half width. */
data class WidgetLayoutRow(
    val first: WorkspaceWidget,
    val second: WorkspaceWidget? = null,
    val firstSpansFullWidth: Boolean = false,
)

/**
 * Pair only adjacent half-span widgets that each accept the available cell width.
 * A full-span widget or a half widget that cannot fit consumes its own full-width row.
 */
fun packWidgetRows(
    widgets: List<WorkspaceWidget>,
    canFitHalf: (WorkspaceWidget) -> Boolean,
): List<WidgetLayoutRow> {
    val rows = ArrayList<WidgetLayoutRow>(widgets.size)
    var index = 0
    while (index < widgets.size) {
        val first = widgets[index]
        if (first.span != 1 || !canFitHalf(first)) {
            rows.add(WidgetLayoutRow(first, firstSpansFullWidth = true))
            index++
            continue
        }
        val second = widgets.getOrNull(index + 1)
        if (second != null && second.span == 1 && canFitHalf(second)) {
            rows.add(WidgetLayoutRow(first, second))
            index += 2
        } else {
            rows.add(WidgetLayoutRow(first))
            index++
        }
    }
    return rows
}
