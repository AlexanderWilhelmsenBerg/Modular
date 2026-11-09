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

    @Test fun fourByFourWidgetAndOneByOneWidgetsPackWithoutOverlap() {
        val preferences = WorkspacePreferences(gridColumnCount = 4, gridRowHeightDp = 96)
        val geometry = calculateWidgetAreaGeometry(440, preferences)
        val widgets = listOf(
            widget(1).copy(columnSpan = 4, rowSpan = 4),
            widget(2).copy(columnSpan = 1, rowSpan = 1),
            widget(3).copy(columnSpan = 1, rowSpan = 1),
            widget(4).copy(columnSpan = 1, rowSpan = 1),
            widget(5).copy(columnSpan = 1, rowSpan = 1),
        )
        val layout = packWidgetGrid(widgets, preferences, geometry)
        assertEquals(listOf(0 to 0, 4 to 0, 4 to 1, 4 to 2, 4 to 3),
            layout.cells.map { it.row to it.column })
        assertEquals(4, layout.cells.first().columnSpan)
        assertEquals(4, layout.cells.first().rowSpan)
        assertEquals(geometry.widthDp, layout.cells.first().widthDp)
        assertEquals(5 * 96 + 4 * 12, layout.totalHeightDp)
        assertTrue(layout.cells.all { it.leftDp >= geometry.leftDp &&
            it.leftDp + it.widthDp <= geometry.rightDp })
    }

    @Test fun savedSlotsAreReservedBeforeAutomaticWidgetsAndConflictsReflow() {
        val preferences = WorkspacePreferences()
        val geometry = calculateWidgetAreaGeometry(440, preferences)
        val widgets = listOf(
            widget(1).copy(columnSpan = 1, rowSpan = 1),
            widget(2).copy(columnSpan = 1, rowSpan = 1, gridColumn = 0, gridRow = 0),
            widget(3).copy(columnSpan = 1, rowSpan = 1, gridColumn = 0, gridRow = 0),
        )
        val cells = packWidgetGrid(widgets, preferences, geometry).cells
        assertEquals(listOf(0 to 1, 0 to 0, 0 to 2), cells.map { it.row to it.column })
        assertEquals(widgets, cells.map { it.widget })
    }

    @Test fun changingColumnCountReflowsOutOfBoundsSlotsWithoutLosingWidgets() {
        val geometry = calculateWidgetAreaGeometry(440, WorkspacePreferences())
        val widgets = listOf(
            widget(1).copy(columnSpan = 1, rowSpan = 1, gridColumn = 3, gridRow = 0),
            widget(2).copy(columnSpan = 4, rowSpan = 4),
        )
        val preferences = WorkspacePreferences(gridColumnCount = 2)
        val cells = packWidgetGrid(widgets, preferences, geometry).cells
        assertEquals(listOf(0 to 0, 1 to 0), cells.map { it.row to it.column })
        assertEquals(listOf(1, 2), cells.map { it.widget.appWidgetId })
        assertEquals(2, cells.last().columnSpan)
    }

    @Test fun legacyPlacementKeepsContentHeightAndExpandsForProviderMinimum() {
        val preferences = WorkspacePreferences(gridColumnCount = 4, gridRowHeightDp = 96)
        val geometry = calculateWidgetAreaGeometry(440, preferences)
        val legacy = widget(1, span = 1).copy(heightDp = 220, paddingDp = 8)
        val span = resolveWidgetGridSpan(legacy, preferences, geometry,
            minWidthDp = gridSpanWidthDp(3, geometry, preferences), minHeightDp = 250)
        assertEquals(4, span.columnSpan)
        assertEquals(3, span.rowSpan)
        assertTrue(gridSpanHeightDp(span.rowSpan, preferences) - 2 * legacy.paddingDp >= 250)
    }

    @Test fun gridBandsNeverSplitWidgetsAndCoverTheEntireLayout() {
        val preferences = WorkspacePreferences()
        val geometry = calculateWidgetAreaGeometry(440, preferences)
        val widgets = listOf(
            widget(1).copy(columnSpan = 1, rowSpan = 4, gridColumn = 0, gridRow = 0),
            widget(2).copy(columnSpan = 1, rowSpan = 4, gridColumn = 1, gridRow = 1),
            widget(3).copy(columnSpan = 4, rowSpan = 4, gridColumn = 0, gridRow = 8),
            widget(4).copy(columnSpan = 1, rowSpan = 1, gridColumn = 0, gridRow = 256),
        )
        val layout = packWidgetGrid(widgets, preferences, geometry)
        val bands = groupWidgetGridBands(layout)
        assertEquals(layout.totalHeightDp, bands.sumOf { it.heightDp })
        assertEquals(0, bands.first().topDp)
        assertTrue(bands.zipWithNext().all { (a, b) -> a.topDp + a.heightDp == b.topDp })
        assertEquals(layout.cells.map { it.widget.appWidgetId }.toSet(),
            bands.flatMap { it.cells }.map { it.widget.appWidgetId }.toSet())
        bands.forEach { band -> band.cells.forEach { cell ->
            assertTrue(cell.topDp >= band.topDp)
            assertTrue(cell.topDp + cell.heightDp <= band.topDp + band.heightDp)
        } }
        assertEquals(setOf(1, 2), bands.first().cells.map { it.widget.appWidgetId }.toSet())
        assertTrue(bands.filter { it.cells.isEmpty() }.all {
            it.heightDp <= MAX_EMPTY_WIDGET_GRID_BAND_HEIGHT_DP
        })
    }

    @Test fun fullWidthWidgetsCreateSeparateBandsForLazyRendering() {
        val preferences = WorkspacePreferences()
        val geometry = calculateWidgetAreaGeometry(440, preferences)
        val widgets = (1..128).map { widget(it).copy(columnSpan = 4, rowSpan = 4) }
        val bands = groupWidgetGridBands(packWidgetGrid(widgets, preferences, geometry))
        assertEquals(128, bands.count { it.cells.isNotEmpty() })
        assertTrue(bands.filter { it.cells.isNotEmpty() }.all { it.cells.size == 1 })
    }
}
