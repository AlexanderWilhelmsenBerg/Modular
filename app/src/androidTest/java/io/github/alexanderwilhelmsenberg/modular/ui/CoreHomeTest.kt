package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppProfile
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import org.junit.Assert.assertEquals
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

        composeRule.onNodeWithTag("home-settings").performClick()
        composeRule.onNodeWithTag("home-make-default").performClick()
        composeRule.runOnIdle {
            assertEquals(true, openedSettings)
            assertEquals(true, madeDefault)
        }
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
