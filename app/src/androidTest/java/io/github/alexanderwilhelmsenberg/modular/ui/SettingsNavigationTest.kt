package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import org.junit.Rule
import org.junit.Test

/** Navigation through the real activity, including state restoration. */
class SettingsNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun subpageBackReturnsToLandingAndLandingBackReturnsHome() {
        openSettings()
        rule.onNodeWithTag("settings-open-productivity").performClick()
        rule.onNodeWithText("Productivity").assertIsDisplayed()
        rule.onNodeWithTag("settings-back").performClick()
        rule.onNodeWithTag("settings-root-title").assertIsDisplayed()
        rule.onNodeWithTag("settings-open-appearance").assertIsDisplayed()
        rule.onNodeWithTag("settings-back").performClick()
        rule.onNodeWithTag("home-search").assertIsDisplayed()
    }

    @Test fun selectedPageSurvivesRecreationAndOtherControlsRemainReachable() {
        openSettings()
        rule.onNodeWithTag("settings-open-appearance").performClick()
        rule.onNodeWithTag("appearance-light").assertIsDisplayed()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("appearance-light").assertIsDisplayed()
        rule.onNodeWithTag("settings-back").performClick()

        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-open-advanced"))
        rule.onNodeWithTag("settings-open-advanced").performClick()
        val diagnosticsButton = hasText("Diagnostics") and hasClickAction()
        rule.onNodeWithTag("settings-list").performScrollToNode(diagnosticsButton)
        rule.onNode(diagnosticsButton).performClick()
        rule.onNodeWithText("Module registry").assertExists()
        rule.onNodeWithText("Core event bus").assertExists()
        rule.onNodeWithTag("settings-back").performClick()

        rule.onNodeWithTag("settings-open-addons").performClick()
        rule.onNodeWithText("Refresh module list").assertExists()
        rule.onNodeWithTag("settings-back").performClick()
        rule.onNodeWithTag("settings-root-title").assertIsDisplayed()
    }

    private fun openSettings() {
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("home-settings").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("home-settings").performClick()
        rule.onNodeWithTag("settings-root-title").assertIsDisplayed()
    }
}
