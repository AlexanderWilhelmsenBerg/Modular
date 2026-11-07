package io.github.alexanderwilhelmsenberg.modular.mostused

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Exercises the real Core settings and Home without selecting an add-on provider. */
class CoreMostUsedIntegrationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun coreTogglePersistsAndOwnsStarWithoutUsageAccess(): Unit = runBlocking {
        val store = MostUsedSettingsStore(rule.activity)
        val original = store.currentSettings()
        try {
            store.setSuggestedCount(0).getOrThrow()
            store.setEnabled(false).getOrThrow()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("home-settings").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("home-settings").performClick()
            waitToggle(ToggleableState.Off)
            rule.onNodeWithTag("core-most-used-toggle").performClick()
            waitToggle(ToggleableState.On)
            rule.onNodeWithText("Back").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("section:★").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("section:★").assertIsDisplayed()
            rule.activityRule.scenario.recreate()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("section:★").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("section:★").assertIsDisplayed()
            rule.onNodeWithTag("home-settings").performClick()
            waitToggle(ToggleableState.On)
            rule.onNodeWithTag("core-most-used-toggle").performClick()
            waitToggle(ToggleableState.Off)
            rule.onNodeWithText("Back").performClick()
            rule.onNodeWithTag("section:★").assertDoesNotExist()
            rule.onNodeWithTag("home-search").assertIsDisplayed()
        } finally {
            store.setSuggestedCount(original.suggestedCount).getOrThrow()
            store.setEnabled(original.enabled).getOrThrow()
        }
    }

    private fun waitToggle(value: ToggleableState) {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("core-most-used-toggle").fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.ToggleableState) == value
            }
        }
    }
}
