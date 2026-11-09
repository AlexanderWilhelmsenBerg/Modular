package io.github.alexanderwilhelmsenberg.modular.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutTest {
    private fun widget(id: Int, span: Int = 1) = WorkspaceWidget(
        id, WorkspacePageId.HOME, "example/.Widget", 160, span = span)

    @Test fun adjacentFittingHalfWidgetsShareRowsWithoutReordering() {
        val widgets = listOf(widget(1), widget(2), widget(3), widget(4), widget(5))
        val rows = packWidgetRows(widgets) { true }
        assertEquals(listOf(1 to 2, 3 to 4, 5 to null), rows.map {
            it.first.appWidgetId to it.second?.appWidgetId
        })
        assertTrue(rows.none { it.firstSpansFullWidth })
    }

    @Test fun fullWidgetInterruptsPairingAndKeepsItsOwnRow() {
        val rows = packWidgetRows(listOf(widget(1), widget(2, span = 2), widget(3), widget(4))) { true }
        assertEquals(listOf(1 to null, 2 to null, 3 to 4), rows.map {
            it.first.appWidgetId to it.second?.appWidgetId
        })
        assertEquals(listOf(false, true, false), rows.map { it.firstSpansFullWidth })
    }

    @Test fun halfWidgetThatCannotFitTakesFullRowAndDoesNotPullLaterWidgetsForward() {
        val rows = packWidgetRows(listOf(widget(1), widget(2), widget(3), widget(4))) {
            it.appWidgetId != 2
        }
        assertEquals(listOf(1 to null, 2 to null, 3 to 4), rows.map {
            it.first.appWidgetId to it.second?.appWidgetId
        })
        assertEquals(listOf(false, true, false), rows.map { it.firstSpansFullWidth })
    }

    @Test fun narrowCellMakesAllHalfWidgetsUseFullRows() {
        val area = calculateWidgetAreaGeometry(180, WorkspacePreferences())
        val rows = packWidgetRows(listOf(widget(1), widget(2))) {
            area.halfCellWidthDp >= 100
        }
        assertEquals(listOf(1, 2), rows.map { it.first.appWidgetId })
        assertTrue(rows.all { it.second == null && it.firstSpansFullWidth })
    }

    @Test fun zeroMarginAndFullWidthUseEveryDpBeforeRail() {
        val geometry = calculateWidgetAreaGeometry(400, WorkspacePreferences(
            widgetAreaWidthPercent = 100, widgetMarginDp = 0, widgetGapDp = 12))
        assertEquals(0, geometry.leftDp)
        assertEquals(344, geometry.widthDp)
        assertEquals(344, geometry.rightDp)
        assertEquals(12, geometry.gapDp)
        assertEquals(166, geometry.halfCellWidthDp)
    }

    @Test fun selectedWidthIsCenteredInsideMargins() {
        val geometry = calculateWidgetAreaGeometry(400, WorkspacePreferences(
            widgetAreaWidthPercent = 50, widgetMarginDp = 20, widgetGapDp = 10))
        assertEquals(96, geometry.leftDp)
        assertEquals(152, geometry.widthDp)
        assertEquals(10, geometry.gapDp)
        assertEquals(71, geometry.halfCellWidthDp)
    }

    @Test fun narrowScreensClampMarginAndGapWithoutNegativeCells() {
        val prefs = WorkspacePreferences(widgetMarginDp = 48, widgetGapDp = 48)
        assertEquals(WidgetAreaGeometry(0, 0, 0, 0), calculateWidgetAreaGeometry(40, prefs))
        assertEquals(WidgetAreaGeometry(1, 2, 0, 1), calculateWidgetAreaGeometry(60, prefs))
        assertEquals(WidgetAreaGeometry(2, 2, 0, 1), calculateWidgetAreaGeometry(62, prefs))
    }
}
