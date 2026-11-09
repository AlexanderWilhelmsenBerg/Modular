package io.github.alexanderwilhelmsenberg.modular.ui

import io.github.alexanderwilhelmsenberg.modular.organization.CategoryDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.HomePageId
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePage
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherSurfacePagesTest {
    private val games = HomePageId.Category("games")
    private val media = HomePageId.Category("media")
    private val weather = HomePageId.Widget("weather")
    private val notes = HomePageId.Widget("notes")

    @Test fun interleavedSavedOrderSplitsIntoHomeAndDrawerSequences() {
        val snapshot = OrganizationSnapshot(
            categoriesEnabled = true,
            categories = listOf(CategoryDefinition("games", "Games", enabled = true),
                CategoryDefinition("media", "Media", enabled = true)),
            workspacePages = listOf(WorkspacePage("weather", "Weather"),
                WorkspacePage("notes", "Notes")),
            pageOrder = listOf(HomePageId.Home, games, weather, media, notes),
        )

        assertEquals(listOf(HomePageId.Home, weather, notes), snapshot.workspaceSurfacePages())
        assertEquals(listOf(HomePageId.Home, games, media), snapshot.drawerSurfacePages())
    }

    @Test fun eachSurfaceSkipsDisabledPagesAndKeepsHome() {
        val snapshot = OrganizationSnapshot(
            categoriesEnabled = true,
            categories = listOf(CategoryDefinition("games", "Games", enabled = false),
                CategoryDefinition("media", "Media", enabled = true)),
            workspacePages = listOf(WorkspacePage("weather", "Weather", enabled = false),
                WorkspacePage("notes", "Notes")),
            pageOrder = listOf(HomePageId.Home, games, weather, media, notes),
        )

        assertEquals(listOf(HomePageId.Home, notes), snapshot.workspaceSurfacePages())
        assertEquals(listOf(HomePageId.Home, media), snapshot.drawerSurfacePages())
    }
}
