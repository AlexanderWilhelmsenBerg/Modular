package io.github.alexanderwilhelmsenberg.modular.widgets

import io.github.alexanderwilhelmsenberg.modular.workspace.*
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetGridSizingTest {
    private val preferences = WorkspacePreferences(gridColumnCount = 4, gridRowHeightDp = 96)
    private val geometry = calculateWidgetAreaGeometry(400, preferences)

    @Test fun advertisedFourByFourIsNotCollapsedToItsSmallResizeMinimum() {
        assertEquals(WidgetGridSpan(4, 4), initialWidgetGridSpan(
            preferences, geometry, 4, 4, 48, 48))
    }

    @Test fun oneByOneRemainsOneCellWhenTheProviderFits() {
        assertEquals(WidgetGridSpan(1, 1), initialWidgetGridSpan(
            preferences, geometry, 1, 1, 32, 32))
    }

    @Test fun OlderProviderWithoutCellMetadataUsesItsInitialMinimumHeight() {
        assertEquals(WidgetGridSpan(4, 4), initialWidgetGridSpan(
            preferences, geometry, 0, 0, 300, 400))
    }

    @Test fun narrowerGridClampsColumnsWithoutCollapsingAdvertisedRows() {
        val twoColumns = preferences.copy(gridColumnCount = 2)
        assertEquals(WidgetGridSpan(2, 4), initialWidgetGridSpan(
            twoColumns, calculateWidgetAreaGeometry(400, twoColumns), 4, 4, 48, 48))
    }
}
