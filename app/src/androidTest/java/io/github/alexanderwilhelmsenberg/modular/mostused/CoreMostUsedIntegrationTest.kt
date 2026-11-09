package io.github.alexanderwilhelmsenberg.modular.mostused

import android.content.Intent
import android.os.Process
import android.os.UserManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.Lifecycle
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
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
            rule.onNodeWithTag("settings-open-productivity").performClick()
            waitToggle(ToggleableState.Off)
            rule.onNodeWithTag("core-most-used-toggle").performClick()
            waitToggle(ToggleableState.On)
            rule.onNodeWithTag("settings-back").performClick()
            rule.onNodeWithTag("settings-back").performClick()
            rule.onNodeWithTag("workspace-apps-action").performClick()
            jumpToMostUsed()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("section:★").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("section:★").assertIsDisplayed()
            rule.activityRule.scenario.recreate()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("section:★").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("section:★").assertIsDisplayed()
            rule.onNodeWithTag("home-settings").performClick()
            rule.onNodeWithTag("settings-open-productivity").performClick()
            waitToggle(ToggleableState.On)
            rule.onNodeWithTag("core-most-used-toggle").performClick()
            waitToggle(ToggleableState.Off)
            rule.onNodeWithTag("settings-back").performClick()
            rule.onNodeWithTag("settings-back").performClick()
            rule.onNodeWithText("Most Used").assertDoesNotExist()
            rule.onNodeWithTag("home-search").assertIsDisplayed()
        } finally {
            store.setSuggestedCount(original.suggestedCount).getOrThrow()
            store.setEnabled(original.enabled).getOrThrow()
        }
    }

    @Test fun launchThroughSearchLearnsWithoutUsageAccessAndSurvivesRecreation(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = MostUsedSettingsStore(context)
        val history = LocalMostUsedHistoryStore(context)
        val original = store.currentSettings()
        val originalIntent = rule.activity.intent
        val fixture = AppKey(
            "io.github.alexanderwilhelmsenberg.modular.testlauncher", "android.app.Activity",
            context.getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle()),
        )
        val rowTag = "app-row:${fixture.stableId}"
        try {
            history.reset().getOrThrow()
            store.setSource(MostUsedSource.LOCAL).getOrThrow()
            store.setSuggestedCount(1).getOrThrow()
            store.setEnabled(true).getOrThrow()
            // Most Used is at the top of the Apps drawer.
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("workspace-home").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("workspace-home").performScrollToIndex(0)
            rule.onNodeWithTag("workspace-apps-action").performClick()
            jumpToMostUsed()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("section:★").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("featured-permission").assertDoesNotExist()
            rule.onNodeWithTag("home-search-field").performTextInput(fixture.packageName)
            rule.waitUntil(10_000) { rule.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodesWithTag(rowTag)[0].performClick()
            withTimeout(10_000) {
                while ((history.currentHistory().scores(7)[fixture] ?: 0.0) <= 0.0) delay(20)
            }
            // ActivityScenario filters lifecycle events by its original launch intent.
            // Preserve that identity, but remove its CLEAR_TASK flag so returning
            // resumes the existing singleTask activity instead of destroying it.
            // HOME intent handling is exercised separately by CoreNavigationTest.
            context.startActivity(Intent(originalIntent).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            rule.waitUntil(15_000) { rule.activityRule.scenario.state == Lifecycle.State.RESUMED }
            rule.onNodeWithTag("home-search-field").performTextClearance()
            rule.onNodeWithTag("home-search-field").performImeAction()
            jumpToMostUsed()
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("section:★").fetchSemanticsNodes().isNotEmpty() &&
                    rule.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onAllNodesWithTag(rowTag)[0].assertIsDisplayed()
            rule.onNodeWithTag("featured-permission").assertDoesNotExist()
            rule.activityRule.scenario.recreate()
            jumpToMostUsed()
            rule.waitUntil(15_000) {
                val list = rule.onNodeWithTag("home-list").fetchSemanticsNode()
                list.config[SemanticsProperties.IndexForKey]("featured:${fixture.stableId}") >= 0 &&
                    runCatching { rule.onNodeWithTag("section:★").assertIsDisplayed() }.isSuccess &&
                    runCatching { rule.onAllNodesWithTag(rowTag)[0].assertIsDisplayed() }.isSuccess
            }
            rule.onNodeWithTag("section:★").assertIsDisplayed()
            rule.onAllNodesWithTag(rowTag)[0].assertIsDisplayed()
            rule.onNodeWithTag("home-settings").performClick()
            rule.onNodeWithTag("settings-open-productivity").performClick()
            rule.onNodeWithText("Configure Most Used").performScrollTo().assertIsDisplayed().performClick()
            rule.onNodeWithText("Reset learned usage…").performScrollTo().performClick()
            rule.onNodeWithText("Reset learned usage").performClick()
            withTimeout(10_000) {
                while (history.currentHistory().scores(30).isNotEmpty()) delay(20)
            }
            assertTrue(store.currentSettings().enabled)
        } finally {
            history.reset().getOrThrow()
            store.setSource(original.source).getOrThrow()
            store.setSuggestedCount(original.suggestedCount).getOrThrow()
            store.setEnabled(original.enabled).getOrThrow()
        }
    }

    private fun jumpToMostUsed() {
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("rail:★").fetchSemanticsNodes().isNotEmpty() }
        val glyph = rule.onNodeWithTag("rail:★").fetchSemanticsNode().boundsInRoot
        val lane = rule.onNodeWithTag("alphabet-hit-lane")
        val bounds = lane.fetchSemanticsNode().boundsInRoot
        lane.performTouchInput {
            down(Offset(width / 2f, glyph.center.y - bounds.top))
            up()
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
