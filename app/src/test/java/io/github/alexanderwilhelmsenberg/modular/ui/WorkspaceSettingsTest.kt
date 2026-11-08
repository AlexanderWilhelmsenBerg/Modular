package io.github.alexanderwilhelmsenberg.modular.ui

import io.github.alexanderwilhelmsenberg.modular.organization.HomePageId
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceSettingsTest {
    @Test fun categoryAndWidgetPagesCanCrossPermanentHome() {
        val category = HomePageId.Category("work")
        val widget = HomePageId.Widget("notes")
        val original = listOf(category, HomePageId.Home, widget)

        assertEquals(listOf(widget, category, HomePageId.Home), movedWorkspacePage(original, 2, 0))
        assertEquals(listOf(category, widget, HomePageId.Home), movedWorkspacePage(original, 2, 1))
        assertEquals(original, movedWorkspacePage(original, 1, 0))
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
