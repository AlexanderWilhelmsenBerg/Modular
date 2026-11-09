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

/** Resolved grid dimensions after legacy sizes and provider minimums are applied. */
data class WidgetGridSpan(val columnSpan: Int, val rowSpan: Int)

/** A placed widget. Coordinates are measured from the physical top-left of the widget area. */
data class WidgetGridCell(
    val widget: WorkspaceWidget,
    val row: Int,
    val column: Int,
    val columnSpan: Int,
    val rowSpan: Int,
    val leftDp: Int,
    val topDp: Int,
    val widthDp: Int,
    val heightDp: Int,
)

data class WidgetGridLayout(val cells: List<WidgetGridCell>, val totalHeightDp: Int)

/**
 * A contiguous vertical slice of a grid. Cell coordinates remain absolute to the grid;
 * subtract [topDp] when drawing a cell within this band. Empty bands represent long gaps.
 */
data class WidgetGridBand(
    val topDp: Int,
    val heightDp: Int,
    val cells: List<WidgetGridCell>,
)

/** Largest empty band; content bands may be taller when widgets form an overlap chain. */
const val MAX_EMPTY_WIDGET_GRID_BAND_HEIGHT_DP = 4_096

/**
 * Partition the grid at cuts that cross no widget. Bands cover 0..totalHeightDp without
 * gaps or overlap, so they can be consecutive items in a vertically scrolling list.
 */
fun groupWidgetGridBands(layout: WidgetGridLayout): List<WidgetGridBand> {
    val bands = ArrayList<WidgetGridBand>()
    var cursor = 0
    fun fillEmptyUntil(endDp: Int) {
        while (cursor < endDp) {
            val height = minOf(endDp - cursor, MAX_EMPTY_WIDGET_GRID_BAND_HEIGHT_DP)
            bands.add(WidgetGridBand(cursor, height, emptyList()))
            cursor += height
        }
    }
    val sorted = layout.cells.sortedBy { it.topDp }
    var componentStart = -1
    var componentBottom = -1
    val componentCells = ArrayList<WidgetGridCell>()
    fun finishComponent() {
        if (componentCells.isEmpty()) return
        fillEmptyUntil(componentStart)
        bands.add(WidgetGridBand(componentStart, componentBottom - componentStart,
            componentCells.toList()))
        cursor = componentBottom
        componentCells.clear()
    }
    sorted.forEach { cell ->
        val bottom = cell.topDp + cell.heightDp
        if (componentCells.isNotEmpty() && cell.topDp >= componentBottom) finishComponent()
        if (componentCells.isEmpty()) {
            componentStart = cell.topDp
            componentBottom = bottom
        } else componentBottom = maxOf(componentBottom, bottom)
        componentCells.add(cell)
    }
    finishComponent()
    fillEmptyUntil(layout.totalHeightDp)
    return bands
}

fun gridGapDp(geometry: WidgetAreaGeometry, preferences: WorkspacePreferences): Int {
    val columns = preferences.gridColumnCount.coerceIn(1, 12)
    return if (columns == 1) 0 else geometry.gapDp.coerceAtMost(
        (geometry.widthDp - columns).coerceAtLeast(0) / (columns - 1))
}

/** Width of the smallest grid column. Any rounding remainder stays at the right edge. */
fun gridCellWidthDp(geometry: WidgetAreaGeometry, preferences: WorkspacePreferences): Int {
    val columns = preferences.gridColumnCount.coerceIn(1, 12)
    return (geometry.widthDp - gridGapDp(geometry, preferences) * (columns - 1))
        .coerceAtLeast(0) / columns
}

fun gridSpanWidthDp(
    span: Int,
    geometry: WidgetAreaGeometry,
    preferences: WorkspacePreferences,
): Int {
    val columns = preferences.gridColumnCount.coerceIn(1, 12)
    val count = span.coerceIn(1, columns)
    // A full-width widget receives any remainder that integer division leaves behind.
    return if (count == columns) geometry.widthDp else
        count * gridCellWidthDp(geometry, preferences) +
            (count - 1) * gridGapDp(geometry, preferences)
}

fun gridSpanHeightDp(span: Int, preferences: WorkspacePreferences): Int {
    val rows = span.coerceIn(1, 128)
    val rowHeight = preferences.gridRowHeightDp.coerceIn(32, 256)
    val gap = preferences.widgetGapDp.coerceIn(0, 48)
    return rows * rowHeight + (rows - 1) * gap
}

/**
 * Resolve old half/full-width and content-height values into the current grid. Provider
 * minimum dimensions include only the widget content, so padding is added before fitting.
 */
fun resolveWidgetGridSpan(
    widget: WorkspaceWidget,
    preferences: WorkspacePreferences,
    geometry: WidgetAreaGeometry,
    minWidthDp: Int = 0,
    minHeightDp: Int = 0,
): WidgetGridSpan {
    val columns = preferences.gridColumnCount.coerceIn(1, 12)
    val desiredColumns = if (widget.columnSpan > 0) widget.columnSpan else
        if (widget.span == 1) (columns + 1) / 2 else columns
    val minOuterWidth = minWidthDp.coerceAtLeast(0) + 2 * widget.paddingDp.coerceAtLeast(0)
    val fittedColumns = (desiredColumns.coerceIn(1, columns)..columns).firstOrNull {
        gridSpanWidthDp(it, geometry, preferences) >= minOuterWidth
    } ?: columns
    val minOuterHeight = minHeightDp.coerceAtLeast(0) + 2 * widget.paddingDp.coerceAtLeast(0)
    val desiredHeight = if (widget.rowSpan > 0) minOuterHeight else
        maxOf(minOuterHeight, widget.heightDp.coerceAtLeast(0) + 2 * widget.paddingDp.coerceAtLeast(0))
    val rowsForHeight = (1..128).firstOrNull { gridSpanHeightDp(it, preferences) >= desiredHeight } ?: 128
    return WidgetGridSpan(fittedColumns, maxOf(widget.rowSpan, rowsForHeight).coerceIn(1, 128))
}

/**
 * Valid saved positions claim their slots before automatic widgets are placed. Conflicting or
 * out-of-bounds positions fall back to first-fit, preserving every widget in input order.
 */
fun packWidgetGrid(
    widgets: List<WorkspaceWidget>,
    preferences: WorkspacePreferences,
    geometry: WidgetAreaGeometry,
    minSizeDp: (WorkspaceWidget) -> Pair<Int, Int> = { 0 to 0 },
): WidgetGridLayout {
    if (widgets.isEmpty()) return WidgetGridLayout(emptyList(), 0)
    val columns = preferences.gridColumnCount.coerceIn(1, 12)
    val gap = gridGapDp(geometry, preferences)
    val rowStride = preferences.gridRowHeightDp.coerceIn(32, 256) +
        preferences.widgetGapDp.coerceIn(0, 48)
    val columnStride = gridCellWidthDp(geometry, preferences) + gap
    val sizes = widgets.map { widget ->
        val (minWidth, minHeight) = minSizeDp(widget)
        resolveWidgetGridSpan(widget, preferences, geometry, minWidth, minHeight)
    }
    val occupied = HashSet<Long>()
    val placed = arrayOfNulls<Pair<Int, Int>>(widgets.size)
    fun slot(row: Int, column: Int): Long = row.toLong() * columns + column
    fun available(row: Int, column: Int, size: WidgetGridSpan): Boolean =
        (row until row + size.rowSpan).all { r ->
            (column until column + size.columnSpan).all { c -> slot(r, c) !in occupied }
        }
    fun reserve(row: Int, column: Int, size: WidgetGridSpan) {
        for (r in row until row + size.rowSpan)
            for (c in column until column + size.columnSpan) occupied.add(slot(r, c))
    }
    widgets.indices.forEach { index ->
        val widget = widgets[index]
        val size = sizes[index]
        if (widget.gridRow in 0..256 && widget.gridColumn in 0..(columns - size.columnSpan) &&
            available(widget.gridRow, widget.gridColumn, size)) {
            reserve(widget.gridRow, widget.gridColumn, size)
            placed[index] = widget.gridRow to widget.gridColumn
        }
    }
    widgets.indices.filter { placed[it] == null }.forEach { index ->
        val size = sizes[index]
        // At most 128 legacy widgets of up to 128 rows plus the highest saved row.
        val row = (0..16_640).first { candidate ->
            (0..(columns - size.columnSpan)).any { available(candidate, it, size) }
        }
        val column = (0..(columns - size.columnSpan)).first { available(row, it, size) }
        reserve(row, column, size)
        placed[index] = row to column
    }
    val cells = widgets.mapIndexed { index, widget ->
        val (row, column) = requireNotNull(placed[index])
        val size = sizes[index]
        WidgetGridCell(widget, row, column, size.columnSpan, size.rowSpan,
            geometry.leftDp + column * columnStride, row * rowStride,
            gridSpanWidthDp(size.columnSpan, geometry, preferences),
            gridSpanHeightDp(size.rowSpan, preferences))
    }
    return WidgetGridLayout(cells, cells.maxOf { it.topDp + it.heightDp })
}
