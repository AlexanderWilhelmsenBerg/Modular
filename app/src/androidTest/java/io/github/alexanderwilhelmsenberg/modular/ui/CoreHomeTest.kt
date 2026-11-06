package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppProfile
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CoreHomeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appsAreSortedAlphabeticallyAndEnabledRowsLaunchTheSelectedApp() {
        val launched = mutableListOf<String>()
        val apps = listOf(
            app("zeta.pkg", "Zeta"),
            app("alpha.pkg", "Alpha"),
            app("middle.pkg", "Middle"),
        )

        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(apps = apps),
                onLaunch = { launched += it.label },
                onRefresh = {},
                onMakeDefault = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeRule.onNodeWithText("Middle").assertIsDisplayed()
        composeRule.onNodeWithText("Zeta").assertIsDisplayed()
        val topPositions = listOf("Alpha", "Middle", "Zeta").map {
            composeRule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals("Home apps should be alphabetized", topPositions.sorted(), topPositions)

        composeRule.onNodeWithTag("app-row:0:alpha.pkg/alpha.pkg.MainActivity").performClick()
        composeRule.runOnIdle { assertEquals(listOf("Alpha"), launched) }
    }

    @Test
    fun settingsAndMakeDefaultActionsInvokeTheirCallbacks() {
        var openedSettings = false
        var madeDefault = false

        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(),
                onLaunch = {},
                onRefresh = {},
                onMakeDefault = { madeDefault = true },
                onOpenSettings = { openedSettings = true },
            )
        }

        composeRule.onNodeWithTag("home-settings")
            .assertContentDescriptionEquals("Settings")
            .performClick()
        composeRule.onNodeWithTag("home-make-default").performClick()
        composeRule.runOnIdle {
            assertEquals(true, openedSettings)
            assertEquals(true, madeDefault)
        }
    }

    @Test
    fun searchFiltersAppsAndClearRestoresAlphabeticalList() {
        val apps = listOf(
            app("zeta.pkg", "Zeta"),
            app("alpha.pkg", "Alpha"),
            app("beta.pkg", "Beta"),
        )
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(apps = apps),
                onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
            )
        }

        composeRule.onNodeWithText("Apps").assertDoesNotExist()
        composeRule.onNodeWithTag("home-search-field").assertContentDescriptionEquals("Search apps").performClick()
        composeRule.onNodeWithTag("home-search-field")
            .assertContentDescriptionEquals("Search apps")
            .performTextInput("bet")
        composeRule.onNodeWithText("Beta").assertIsDisplayed()
        composeRule.onNodeWithText("Alpha").assertDoesNotExist()
        composeRule.onNodeWithText("Zeta").assertDoesNotExist()

        composeRule.onNodeWithTag("home-search-clear")
            .assertContentDescriptionEquals("Clear search")
            .performClick()
        listOf("Alpha", "Beta", "Zeta").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        val positions = listOf("Alpha", "Beta", "Zeta").map {
            composeRule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun unmatchedSearchHasDistinctEmptyStateAndSettingsStaysReachable() {
        var settingsOpens = 0
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(apps = listOf(app("alpha.pkg", "Alpha"))),
                onLaunch = {}, onRefresh = {}, onMakeDefault = {},
                onOpenSettings = { settingsOpens += 1 },
            )
        }

        composeRule.onNodeWithTag("home-search-field").performClick()
        composeRule.onNodeWithTag("home-search-field").performTextInput("missing")
        composeRule.onNodeWithText("No matching apps").assertIsDisplayed()
        composeRule.onNodeWithText("No apps available").assertDoesNotExist()
        composeRule.onNodeWithTag("home-settings")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Settings")
            .performClick()
        composeRule.runOnIdle { assertEquals(1, settingsOpens) }
    }

    @Test
    fun persistentSearchFillsRowAndKeepsBoundsWhenFocusedOrTyped() {
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(),
                onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
            )
        }

        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        val restingSearch = composeRule.onNodeWithTag("home-search").fetchSemanticsNode().boundsInRoot
        val settings = composeRule.onNodeWithTag("home-settings").fetchSemanticsNode().boundsInRoot
        assertEquals(48f, restingSearch.height / density, 1.5f)
        assertEquals(48f, settings.height / density, 1.5f)
        assertEquals(48f, settings.width / density, 1.5f)
        assertEquals(restingSearch.height, settings.height, density)
        composeRule.onNodeWithTag("home-search-field").assertIsDisplayed()
        assertTrue("Search should fill the space beside Settings", restingSearch.width > settings.width * 3)
        composeRule.onNodeWithTag("home-search-field").performClick()
        composeRule.onNodeWithTag("home-search-field").performTextInput("A long query that scrolls while the search bar stays the same size")
        val focused = composeRule.onNodeWithTag("home-search").fetchSemanticsNode().boundsInRoot
        assertEquals(restingSearch.width, focused.width, 1f)
        assertEquals(restingSearch.height, focused.height, 1f)
        composeRule.onNodeWithTag("home-search-clear").performClick()
        val cleared = composeRule.onNodeWithTag("home-search").fetchSemanticsNode().boundsInRoot
        assertEquals(restingSearch.width, cleared.width, 1f)
        assertEquals(restingSearch.height, cleared.height, 1f)
        composeRule.onNodeWithTag("home-settings").assertIsDisplayed()
    }

    @Test
    fun compactWidthAndLargeTextKeepSearchAndSettingsUsable() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp)) {
                    HomeScreen(
                        catalog = AppCatalog(apps = listOf(app("alpha.pkg", "Alpha"))),
                        onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("home-search-field").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("home-search-field").assertIsDisplayed().performTextInput("Alpha")
        val alphaKey = app("alpha.pkg", "Alpha").key.stableId
        composeRule.onNodeWithTag("app-row:$alphaKey").assertIsDisplayed()
        composeRule.onNodeWithTag("home-settings").assertIsDisplayed()
    }

    @Test
    fun disabledAppsAndQuietProfilesCannotBeLaunched() {
        val launches = mutableListOf<String>()
        val disabled = app("disabled.pkg", "Disabled", enabled = false)
        val quietProfileApp = app("work.pkg", "Work", profile = 10L)

        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(
                    profiles = listOf(AppProfile(serialNumber = 10L, quietModeEnabled = true)),
                    apps = listOf(disabled, quietProfileApp),
                ),
                onLaunch = { launches += it.label },
                onRefresh = {},
                onMakeDefault = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithTag("app-row:${disabled.key.stableId}").assertIsNotEnabled()
        composeRule.onNodeWithTag("app-row:${quietProfileApp.key.stableId}").assertIsNotEnabled()
        composeRule.onNodeWithText("Some apps are unavailable while a profile is paused.")
            .assertIsDisplayed()
        assertEquals(
            "Unavailable",
            composeRule.onNodeWithTag("app-row:${disabled.key.stableId}")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        assertEquals(
            "Profile paused",
            composeRule.onNodeWithTag("app-row:${quietProfileApp.key.stableId}")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        composeRule.runOnIdle { assertEquals(emptyList<String>(), launches) }
    }

    @Test
    fun loadingCatalogShowsProgress() {
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(isRefreshing = true),
                onLaunch = {},
                onRefresh = {},
                onMakeDefault = {},
                onOpenSettings = {},
            )
        }
        composeRule.onNodeWithText("Loading apps…").assertIsDisplayed()
    }

    @Test
    fun emptyCatalogExplainsThatNoAppsAreAvailable() {
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(),
                onLaunch = {},
                onRefresh = {},
                onMakeDefault = {},
                onOpenSettings = {},
            )
        }
        composeRule.onNodeWithText("No apps available").assertIsDisplayed()
    }

    @Test
    fun catalogErrorCanBeRetried() {
        var refreshes = 0
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(lastError = "Repository unavailable"),
                onLaunch = {},
                onRefresh = { refreshes += 1 },
                onMakeDefault = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithText("Apps could not be loaded").assertIsDisplayed()
        composeRule.onNodeWithText("Repository unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("home-retry").performClick()
        composeRule.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    fun defaultRowsContainOnlyAppNameText() {
        val entry = app("plain.pkg", "Plain app")
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(apps = listOf(entry), profiles = listOf(AppProfile(0L, false))),
                onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
            )
        }
        composeRule.onAllNodes(
            hasAnyAncestor(hasTestTag("app-row:${entry.key.stableId}")) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        composeRule.onAllNodes(
            hasAnyAncestor(hasTestTag("app-row:${entry.key.stableId}")) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    private fun app(
        packageName: String,
        label: String,
        enabled: Boolean = true,
        profile: Long = 0L,
    ) = LaunchableApp(
        key = AppKey(packageName, "$packageName.MainActivity", profile),
        label = label,
        firstInstallTime = 1L,
        enabled = enabled,
        loadingProgress = null,
    )
}
