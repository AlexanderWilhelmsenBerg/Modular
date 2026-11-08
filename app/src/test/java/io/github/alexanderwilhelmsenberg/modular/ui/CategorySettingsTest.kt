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

    @Test fun nameValidationRejectsBlankAndLocaleEquivalentDuplicates() {
        val categories = listOf(CategoryDefinition("games", "Games"))
        assertNotNull(categoryNameError("   ", categories, null))
        assertNotNull(categoryNameError("GAMES", categories, null))
        assertNull(categoryNameError("Games", categories, "games"))
        assertNull(categoryNameError("Travel", categories, null))
    }
}
