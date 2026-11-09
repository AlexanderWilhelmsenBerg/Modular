package io.github.alexanderwilhelmsenberg.modular.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetStageJournalTest {
    @Test fun interruptedExternalFlowRetainsOnlyItsStagedId() {
        val stage = WidgetStage(11, "example/.Provider", "home", WidgetStage.Phase.CONFIGURE_NEW, 1L)
        assertEquals(setOf(13), abandonedWidgetIds(listOf(10, 11, 12, 13), listOf(10, 12), stage))
    }

    @Test fun cancellationMakesAllocatedIdReclaimable() {
        assertEquals(setOf(11), abandonedWidgetIds(listOf(10, 11), listOf(10), null))
    }

    @Test fun persistedPlacementSurvivesClearedStage() {
        assertEquals(emptySet<Int>(), abandonedWidgetIds(listOf(10, 11), listOf(10, 11), null))
    }

    @Test fun outstandingFlowBlocksNewAllocationUntilResultOrCancellation() {
        val stage = WidgetStage(11, "example/.Provider", "home", WidgetStage.Phase.BIND, 1L)
        assertEquals(false, mayStartWidgetFlow(stage, null))
        assertEquals(false, mayStartWidgetFlow(null, 11))
        assertEquals(true, mayStartWidgetFlow(null, null))
    }

    @Test fun lateConfigurationResultCannotCompleteNewerStage() {
        val newer = WidgetStage(12, "example/.Provider", "home", WidgetStage.Phase.CONFIGURE_NEW, 2L)
        assertEquals(false, resultMatchesStage(11, newer))
        assertEquals(true, resultMatchesStage(12, newer))
    }
}
