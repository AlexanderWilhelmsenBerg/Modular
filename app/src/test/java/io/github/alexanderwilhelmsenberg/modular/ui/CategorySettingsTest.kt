package io.github.alexanderwilhelmsenberg.modular.ui

import io.github.alexanderwilhelmsenberg.modular.organization.CategoryDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.HomePageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CategorySettingsTest {
    @Test fun categoryCanCrossHomeAndKeepPhysicalOrder() {
        val work = HomePageId.Category("work")
        val games = HomePageId.Category("games")
        val order = listOf(work, HomePageId.Home, games)
        assertEquals(listOf(HomePageId.Home, games, work), movedCategoryPage(order, 0, 2))
        assertEquals(listOf(work, HomePageId.Home, games), movedCategoryPage(order, 1, 0))
    }

    @Test fun categoryReorderDoesNotMoveWidgetPagesAcrossHome() {
        val c1 = HomePageId.Category("c1")
        val c2 = HomePageId.Category("c2")
        val w1 = HomePageId.Widget("w1")
        val w2 = HomePageId.Widget("w2")
        val original = listOf(w1, c1, HomePageId.Home, w2, c2)
        val category = categoryPageOrder(original)
        assertEquals(listOf(c1, HomePageId.Home, c2), category)
        val moved = movedCategoryPage(category, 0, 2)
        assertEquals(listOf(w1, HomePageId.Home, w2, c2, c1),
            mergeSurfacePageOrder(original, moved, PageOrderSurface.CATEGORY))
    }

    @Test fun nameValidationRejectsBlankAndLocaleEquivalentDuplicates() {
        val categories = listOf(CategoryDefinition("games", "Games"))
        assertNotNull(categoryNameError("   ", categories, null))
        assertNotNull(categoryNameError("GAMES", categories, null))
        assertNull(categoryNameError("Games", categories, "games"))
        assertNull(categoryNameError("Travel", categories, null))
    }
}
