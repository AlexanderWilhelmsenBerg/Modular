package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppCustomizations
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.organization.CategoryDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.HomePageId
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationProjector
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OrganizationHomeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun app(packageName: String, name: String, category: Int? = null) = LaunchableApp(
        AppKey(packageName, "$packageName.Main", 0), name, 1L, true, null, category,
    )

    @Test fun suppressedCategoryAppStillAppearsInGlobalSearchAndLaunches() {
        val game = app("example.game", "Puzzle Game", ApplicationInfo.CATEGORY_GAME)
        val browser = app("example.browser", "Browser")
        val snapshot = OrganizationSnapshot(
            categoriesEnabled = true,
            categories = listOf(CategoryDefinition("games", "Games", enabled = true, showOnHome = false, automatic = true)),
            pageOrder = listOf(HomePageId.Home, HomePageId.Category("games")),
        )
        val projection = OrganizationProjector.project(AppCatalog(apps = listOf(game, browser)), AppCustomizations(), snapshot)
        val launched = mutableListOf<AppKey>()
        composeRule.setContent {
            var query by remember { mutableStateOf("") }
            HomeScreen(
                catalog = AppCatalog(apps = projection.homeApps),
                searchApps = projection.globalSearchApps,
                searchQuery = query,
                onSearchQueryChange = { query = it },
                onLaunch = { launched += it.key },
                onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
                showMakeDefault = false,
            )
        }
        composeRule.onNodeWithTag("app-row:${browser.key.stableId}").assertIsDisplayed()
        composeRule.onNodeWithTag("app-row:${game.key.stableId}").assertDoesNotExist()
        composeRule.onNodeWithTag("home-search-field").performClick()
        composeRule.onNodeWithTag("home-search-field").performTextInput("Puzzle")
        composeRule.onNodeWithTag("app-row:${game.key.stableId}").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(listOf(game.key), launched) }
    }

    @Test fun pagerUsesPhysicalSidesAndHomeResetReturnsFromCategory() {
        val work = HomePageId.Category("work")
        val games = HomePageId.Category("games")
        val snapshot = OrganizationSnapshot(
            categoriesEnabled = true,
            categories = listOf(
                CategoryDefinition("work", "Work", enabled = true),
                CategoryDefinition("games", "Games", enabled = true),
            ),
            pageOrder = listOf(work, HomePageId.Home, games),
        )
        var resetToken by mutableIntStateOf(0)
        composeRule.setContent {
            LauncherPages(snapshot, gesturesEnabled = true, homeResetToken = resetToken, renderPage = { page ->
                Text(when (page) {
                    HomePageId.Home -> "Home page"
                    work -> "Work page"
                    games -> "Games page"
                    else -> "Other page"
                })
            })
        }
        composeRule.onNodeWithTag("launcher-page:home").assertIsDisplayed()
        composeRule.onNodeWithTag("launcher-pages").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("launcher-page:category:games").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("launcher-page:home").assertIsDisplayed()
        composeRule.onNodeWithTag("launcher-pages").performTouchInput { swipeRight() }
        composeRule.onNodeWithTag("launcher-page:category:work").assertIsDisplayed()
        composeRule.runOnIdle { resetToken++ }
        composeRule.onNodeWithTag("launcher-page:home").assertIsDisplayed()
    }

    @Test fun searchFromCategoryFindsOtherAppsAndClearingRestoresItsMembers() {
        val game = app("example.game", "Puzzle Game", ApplicationInfo.CATEGORY_GAME)
        val browser = app("example.browser", "Browser")
        composeRule.setContent {
            var query by remember { mutableStateOf("") }
            HomeScreen(
                catalog = AppCatalog(apps = listOf(game)),
                searchApps = listOf(game, browser),
                pageTitle = "Games",
                searchQuery = query,
                onSearchQueryChange = { query = it },
                onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
                showMakeDefault = false,
            )
        }
        composeRule.onNodeWithTag("app-row:${game.key.stableId}").assertIsDisplayed()
        composeRule.onNodeWithTag("app-row:${browser.key.stableId}").assertDoesNotExist()
        composeRule.onNodeWithTag("home-search-field").performClick()
        composeRule.onNodeWithTag("home-search-field").performTextInput("Browser")
        composeRule.onNodeWithTag("app-row:${browser.key.stableId}").assertIsDisplayed()
        composeRule.onNodeWithTag("home-search-clear").performClick()
        composeRule.onNodeWithTag("app-row:${game.key.stableId}").assertIsDisplayed()
        composeRule.onNodeWithTag("app-row:${browser.key.stableId}").assertDoesNotExist()
    }
}
