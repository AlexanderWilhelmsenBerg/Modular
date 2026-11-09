package io.github.alexanderwilhelmsenberg.modular.ui

import io.github.alexanderwilhelmsenberg.modular.workspace.WidgetGridCell
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceWidgetGridTargetTest {
    private fun cell(column: Int, columnSpan: Int, rowSpan: Int = 1) = WidgetGridCell(
        WorkspaceWidget(42, "home", "fixture/.Provider", 96), row = 1, column = column,
        columnSpan = columnSpan, rowSpan = rowSpan,
        leftDp = 0, topDp = 0, widthDp = 80, heightDp = 96,
    )

    @Test fun draggingBottomLeftOutwardKeepsTopRightFixed() {
        val target = bottomLeftResizeTarget(cell(column = 1, columnSpan = 1),
            dxDp = -80f, dyDp = 104f, columnStepDp = 80, rowStepDp = 104, columns = 4)
        assertEquals(WidgetGridTarget(column = 0, row = 1, columnSpan = 2, rowSpan = 2), target)
        assertEquals(2, target.column + target.columnSpan)
    }

    @Test fun draggingBottomLeftInwardShrinksFromPhysicalLeft() {
        val target = bottomLeftResizeTarget(cell(column = 0, columnSpan = 2, rowSpan = 2),
            dxDp = 80f, dyDp = -104f, columnStepDp = 80, rowStepDp = 104, columns = 4)
        assertEquals(WidgetGridTarget(column = 1, row = 1, columnSpan = 1, rowSpan = 1), target)
        assertEquals(2, target.column + target.columnSpan)
    }

    @Test fun gripStopsAtPhysicalGridBounds() {
        val target = bottomLeftResizeTarget(cell(column = 1, columnSpan = 2),
            dxDp = -1_000f, dyDp = 10_000f, columnStepDp = 80, rowStepDp = 104, columns = 4)
        assertEquals(WidgetGridTarget(column = 0, row = 1, columnSpan = 3, rowSpan = 32), target)
    }
}
