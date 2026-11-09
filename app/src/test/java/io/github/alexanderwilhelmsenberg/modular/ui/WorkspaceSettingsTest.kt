package io.github.alexanderwilhelmsenberg.modular.ui

import io.github.alexanderwilhelmsenberg.modular.organization.HomePageId
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePage
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceSection
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceSettingsTest {
    @Test fun widgetPagesCrossHomeWithoutMovingCategorySides() {
        val leftCategory = HomePageId.Category("work")
        val rightCategory = HomePageId.Category("games")
        val leftWidget = HomePageId.Widget("notes")
        val rightWidget = HomePageId.Widget("music")
        val original = listOf(leftCategory, leftWidget, HomePageId.Home, rightCategory, rightWidget)
        val workspace = workspacePageOrder(original)

        assertEquals(listOf(leftWidget, HomePageId.Home, rightWidget), workspace)
        val moved = movedWorkspacePage(workspace, 2, 0)
        assertEquals(listOf(rightWidget, leftWidget, HomePageId.Home), moved)
        assertEquals(listOf(rightWidget, leftWidget, leftCategory, HomePageId.Home, rightCategory),
            mergeSurfacePageOrder(original, moved, PageOrderSurface.WORKSPACE))
        assertEquals(workspace, movedWorkspacePage(workspace, 1, 0))
    }

    @Test fun movingWidgetAcrossHomePreservesCategorySideAndOrder() {
        val c1 = HomePageId.Category("c1")
        val c2 = HomePageId.Category("c2")
        val w1 = HomePageId.Widget("w1")
        val w2 = HomePageId.Widget("w2")
        val original = listOf(c1, w1, c2, HomePageId.Home, w2)
        val reordered = listOf(w1, w2, HomePageId.Home)
        assertEquals(listOf(w1, w2, c1, c2, HomePageId.Home),
            mergeSurfacePageOrder(original, reordered, PageOrderSurface.WORKSPACE))
    }

    @Test fun namesRejectBlankLongAndCaseAccentInsensitiveDuplicates() {
        val snapshot = OrganizationSnapshot(workspacePages = listOf(WorkspacePage("notes", "Résumé")))

        assertNotNull(workspacePageNameError("  ", snapshot, null))
        assertNotNull(workspacePageNameError("a".repeat(61), snapshot, null))
        assertNotNull(workspacePageNameError("home", snapshot, null))
        assertNotNull(workspacePageNameError("Resume", snapshot, null))
        assertNull(workspacePageNameError("Résumé", snapshot, "notes"))
        assertNull(workspacePageNameError("Reading", snapshot, null))
    }

    @Test fun homeSectionDraftKeepsLegacyMostUsedSlot() {
        val stored = listOf(WorkspaceSection.CLOCK, WorkspaceSection.MOST_USED,
            WorkspaceSection.WIDGETS, WorkspaceSection.LISTENING)
        val draft = listOf(WorkspaceSection.LISTENING, WorkspaceSection.WIDGETS,
            WorkspaceSection.CLOCK)

        assertEquals(listOf(WorkspaceSection.LISTENING, WorkspaceSection.MOST_USED,
            WorkspaceSection.WIDGETS, WorkspaceSection.CLOCK), stored.withHomeSectionOrder(draft))
        assertEquals(stored, stored.withHomeSectionOrder(listOf(WorkspaceSection.CLOCK)))
    }

    @Test fun widgetOrderDraftReflowsOnlyItsPageAndKeepsStableIds() {
        val first = WorkspaceWidget(11, "home", "test/First", heightDp = 96,
            columnSpan = 2, rowSpan = 2, gridColumn = 0, gridRow = 0)
        val otherPage = WorkspaceWidget(12, "widget:notes", "test/Other", heightDp = 96,
            columnSpan = 1, rowSpan = 1, gridColumn = 3, gridRow = 5)
        val second = WorkspaceWidget(13, "home", "test/Second", heightDp = 96,
            columnSpan = 1, rowSpan = 1, gridColumn = 2, gridRow = 0)
        val stored = listOf(first, otherPage, second)

        assertEquals(listOf(13, 11, 12), normalizedWidgetOrderDraft(listOf(13, 11, 99),
            listOf(11, 12, 13)))
        val result = stored.withWidgetPageOrder("home", listOf(13, 11))
        assertEquals(listOf(13, 12, 11), result.map { it.appWidgetId })
        assertEquals(otherPage, result[1])
        assertEquals(listOf(-1, -1), listOf(result[0].gridColumn, result[0].gridRow))
        assertEquals(listOf(-1, -1), listOf(result[2].gridColumn, result[2].gridRow))
        assertEquals(second.columnSpan, result[0].columnSpan)
        assertEquals(first.rowSpan, result[2].rowSpan)
    }
}
