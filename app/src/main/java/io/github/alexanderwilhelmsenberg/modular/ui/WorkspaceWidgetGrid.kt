package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.widgets.WidgetLayoutLimits
import io.github.alexanderwilhelmsenberg.modular.workspace.WidgetAreaGeometry
import io.github.alexanderwilhelmsenberg.modular.workspace.WidgetGridBand
import io.github.alexanderwilhelmsenberg.modular.workspace.WidgetGridCell
import io.github.alexanderwilhelmsenberg.modular.workspace.WidgetGridLayout
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePreferences
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import io.github.alexanderwilhelmsenberg.modular.workspace.gridCellWidthDp
import io.github.alexanderwilhelmsenberg.modular.workspace.gridGapDp
import io.github.alexanderwilhelmsenberg.modular.workspace.gridSpanHeightDp
import io.github.alexanderwilhelmsenberg.modular.workspace.gridSpanWidthDp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Destination after moving a widget or dragging its resize grip. */
internal data class WidgetGridTarget(
    val column: Int,
    val row: Int,
    val columnSpan: Int,
    val rowSpan: Int,
)

/** The top-right grid edge stays fixed while the bottom-left grip moves. */
internal fun bottomLeftResizeTarget(
    cell: WidgetGridCell,
    dxDp: Float,
    dyDp: Float,
    columnStepDp: Int,
    rowStepDp: Int,
    columns: Int,
): WidgetGridTarget {
    val right = cell.column + cell.columnSpan
    val left = (cell.column + (dxDp / columnStepDp.coerceAtLeast(1)).roundToInt())
        .coerceIn(0, (right - 1).coerceAtLeast(0))
    val rowDelta = (dyDp / rowStepDp.coerceAtLeast(1)).roundToInt()
    val height = if (rowDelta == 0) cell.rowSpan else
        (cell.rowSpan + rowDelta).coerceIn(1, 32)
    return WidgetGridTarget(left.coerceAtMost(columns - 1), cell.row,
        (right - left).coerceIn(1, columns), height)
}

/** One non-scrolling grid band within Home's lazy list. */
@Composable
internal fun WorkspaceWidgetGrid(
    layout: WidgetGridLayout,
    band: WidgetGridBand,
    preferences: WorkspacePreferences,
    geometry: WidgetAreaGeometry,
    editMode: Boolean,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onEmptyLongPress: () -> Unit,
    limits: (WorkspaceWidget) -> WidgetLayoutLimits?,
    onSave: suspend (Int, Int, Int, Int, Int) -> Result<Unit>,
    widgetContent: @Composable (WorkspaceWidget, () -> Unit) -> Unit,
) {
    val columns = preferences.gridColumnCount.coerceIn(1, 12)
    val rowHeight = preferences.gridRowHeightDp.coerceIn(32, 256)
    val gap = gridGapDp(geometry, preferences)
    val rowGap = preferences.widgetGapDp.coerceIn(0, 48)
    val cellWidth = gridCellWidthDp(geometry, preferences)
    val scope = rememberCoroutineScope()
    var savingId by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val hasSelection = band.cells.any { it.widget.appWidgetId == selectedId }
    val extraHeight = if (editMode && hasSelection)
        rowHeight + rowGap else 0
    val totalHeight = (band.heightDp + extraHeight)
        .coerceAtLeast(if (editMode && band.cells.isEmpty() && band.heightDp == 0) rowHeight * 2 else 0)
    val emptyHold = if (editMode) Modifier else Modifier.pointerInput(band, density) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Native provider content keeps its entire pointer stream, even when its
            // AndroidView does not consume the initial DOWN event.
            val insideWidget = band.cells.any { cell ->
                val left = with(density) { cell.leftDp.dp.toPx() }
                val top = with(density) { (cell.topDp - band.topDp).dp.toPx() }
                val right = left + with(density) { cell.widthDp.dp.toPx() }
                val bottom = top + with(density) { cell.heightDp.dp.toPx() }
                down.position.x >= left && down.position.x < right &&
                    down.position.y >= top && down.position.y < bottom
            }
            if (insideWidget) {
                var pressed: Boolean
                do {
                    pressed = awaitPointerEvent(PointerEventPass.Final).changes.any { it.pressed }
                } while (pressed)
            } else {
                awaitLongPressOrCancellation(down.id)?.let { held ->
                    held.consume()
                    onEmptyLongPress()
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(totalHeight.dp).then(emptyHold).testTag("workspace-widget-grid"),
        contentAlignment = AbsoluteAlignment.TopLeft) {
        if (editMode) {
            // The open rows are visible drop targets and leave room to move the last widget down.
            Box(Modifier.absoluteOffset(x = geometry.leftDp.dp).width(geometry.widthDp.dp)
                .height(totalHeight.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(8.dp)))
        }
        band.cells.forEach { cell ->
            val widget = cell.widget
            val selected = selectedId == widget.appWidgetId
            val limit = limits(widget)
            var drag by remember(widget.appWidgetId, editMode) { mutableStateOf(Offset.Zero) }
            var resizing by remember(widget.appWidgetId, editMode) { mutableStateOf(false) }
            val dxDp = with(density) { drag.x.toDp().value }
            val dyDp = with(density) { drag.y.toDp().value }
            val columnStep = (cellWidth + gap).coerceAtLeast(1)
            val rowStep = (rowHeight + rowGap).coerceAtLeast(1)
            val targetColumn = (cell.column + (dxDp / columnStep).roundToInt())
                .coerceIn(0, (columns - cell.columnSpan).coerceAtLeast(0))
            val targetRow = (cell.row + (dyDp / rowStep).roundToInt()).coerceIn(0, 256)
            val candidate = if (resizing) bottomLeftResizeTarget(cell, dxDp, dyDp,
                columnStep, rowStep, columns) else WidgetGridTarget(targetColumn, targetRow,
                cell.columnSpan, cell.rowSpan)
            fun valid(column: Int, row: Int, widthSpan: Int, heightSpan: Int): Boolean {
                val keepLegacyHeight = widget.rowSpan == 0 && heightSpan == cell.rowSpan && heightSpan > 32
                if (column < 0 || row !in 0..256 || column + widthSpan > columns || widthSpan < 1 ||
                    (heightSpan !in 1..32 && !keepLegacyHeight)) return false
                val width = gridSpanWidthDp(widthSpan, geometry, preferences) - 2 * widget.paddingDp
                val height = gridSpanHeightDp(heightSpan, preferences) - 2 * widget.paddingDp
                if (limit != null && (width <= 0 ||
                        (width < limit.minContentWidthDp && widthSpan < columns) ||
                        height < limit.minContentHeightDp || height > limit.maxContentHeightDp ||
                        width > limit.maxContentWidthDp ||
                        (!limit.canResizeWidth && widthSpan != cell.columnSpan) ||
                        (!limit.canResizeHeight && heightSpan != cell.rowSpan))) return false
                return layout.cells.none { other -> other.widget.appWidgetId != widget.appWidgetId &&
                    column < other.column + other.columnSpan && column + widthSpan > other.column &&
                    row < other.row + other.rowSpan && row + heightSpan > other.row }
            }
            val canDrop = valid(candidate.column, candidate.row, candidate.columnSpan, candidate.rowSpan)
            fun save(column: Int, row: Int, widthSpan: Int, heightSpan: Int) {
                if (savingId != null || !valid(column, row, widthSpan, heightSpan) ||
                    (column == cell.column && row == cell.row && widthSpan == cell.columnSpan && heightSpan == cell.rowSpan)) return
                scope.launch {
                    savingId = widget.appWidgetId
                    error = null
                    onSave(widget.appWidgetId, widthSpan, heightSpan, column, row)
                        .onFailure { error = it.message ?: "Could not save widget layout" }
                    savingId = null
                }
            }
            val baseModifier = Modifier.absoluteOffset(x = cell.leftDp.dp, y = (cell.topDp - band.topDp).dp)
                .width(cell.widthDp.dp).height(cell.heightDp.dp)
            Box(baseModifier.testTag("workspace-widget-cell:${widget.appWidgetId}"),
                contentAlignment = AbsoluteAlignment.TopLeft) {
                val contentHeight = (cell.heightDp - widget.paddingDp * 2).coerceAtLeast(1)
                widgetContent(widget.copy(heightDp = contentHeight), { onLongPress(widget.appWidgetId) })
                if (editMode) {
                    Box(Modifier.matchParentSize()
                        .border(if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp))
                        .clickable { onSelect(widget.appWidgetId) }
                        .pointerInput(widget.appWidgetId, layout.cells, savingId) {
                            detectDragGesturesAfterLongPress(onDragStart = {
                                onSelect(widget.appWidgetId)
                                drag = Offset.Zero
                                resizing = false
                            }, onDragEnd = {
                                val x = with(density) { drag.x.toDp().value }
                                val y = with(density) { drag.y.toDp().value }
                                save((cell.column + (x / columnStep).roundToInt())
                                    .coerceIn(0, (columns - cell.columnSpan).coerceAtLeast(0)),
                                    (cell.row + (y / rowStep).roundToInt()).coerceIn(0, 256),
                                    cell.columnSpan, cell.rowSpan)
                                drag = Offset.Zero
                            }, onDragCancel = { drag = Offset.Zero }) { change, amount ->
                                change.consume()
                                drag += amount
                            }
                        }.testTag("workspace-widget-move:${widget.appWidgetId}"))
                    if (selected) {
                        val color = if (canDrop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        if (drag != Offset.Zero) Box(Modifier.absoluteOffset {
                            IntOffset((candidate.column - cell.column) * with(density) { columnStep.dp.roundToPx() },
                                (candidate.row - cell.row) * with(density) { rowStep.dp.roundToPx() })
                        }.width(gridSpanWidthDp(candidate.columnSpan, geometry, preferences).dp)
                            .height(gridSpanHeightDp(candidate.rowSpan, preferences).dp)
                            .border(2.dp, color, RoundedCornerShape(8.dp))
                            .background(color.copy(alpha = 0.14f)))
                        val canResize = limit?.canResizeWidth == true || limit?.canResizeHeight == true
                        val gripSizeDp = minOf(32, cell.widthDp / 2, cell.heightDp / 2).coerceAtLeast(1)
                        if (canResize) Canvas(Modifier.align(AbsoluteAlignment.BottomLeft)
                            .size(gripSizeDp.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                            .pointerInput(widget.appWidgetId, layout.cells, savingId) {
                                detectDragGestures(onDragStart = { resizing = true }, onDragEnd = {
                                    val x = with(density) { drag.x.toDp().value }
                                    val y = with(density) { drag.y.toDp().value }
                                    val target = bottomLeftResizeTarget(cell, x, y,
                                        columnStep, rowStep, columns)
                                    save(target.column, target.row, target.columnSpan, target.rowSpan)
                                    drag = Offset.Zero; resizing = false
                                }, onDragCancel = { drag = Offset.Zero; resizing = false }) { change, amount ->
                                    change.consume()
                                    drag += Offset(if (limit?.canResizeWidth == true) amount.x else 0f,
                                        if (limit?.canResizeHeight == true) amount.y else 0f)
                                }
                            }.testTag("workspace-widget-resize:${widget.appWidgetId}")) {
                            val stroke = minOf(2.dp.toPx(), size.minDimension / 4f)
                            val gripColor = color
                            drawLine(gripColor, Offset(size.width * .25f, size.height * .75f),
                                Offset(size.width * .75f, size.height * .25f), stroke,
                                cap = StrokeCap.Round)
                            drawLine(gripColor, Offset(size.width * .25f, size.height * .75f),
                                Offset(size.width * .25f, size.height * .45f), stroke,
                                cap = StrokeCap.Round)
                            drawLine(gripColor, Offset(size.width * .25f, size.height * .75f),
                                Offset(size.width * .55f, size.height * .75f), stroke,
                                cap = StrokeCap.Round)
                        }
                    }
                }
            }
        }
        if (error != null) Text(error.orEmpty(), Modifier.align(Alignment.BottomStart).padding(8.dp),
            color = MaterialTheme.colorScheme.error)
    }
}
