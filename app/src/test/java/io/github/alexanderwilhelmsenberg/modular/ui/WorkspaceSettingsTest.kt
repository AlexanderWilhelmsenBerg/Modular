package io.github.alexanderwilhelmsenberg.modular.ui

import io.github.alexanderwilhelmsenberg.modular.organization.HomePageId
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePage
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
}
