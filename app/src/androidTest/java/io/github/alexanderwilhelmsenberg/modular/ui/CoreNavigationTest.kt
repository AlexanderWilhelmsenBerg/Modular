package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.Intent
import android.content.ComponentName
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import org.junit.Rule
import org.junit.Test

class CoreNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsBackReturnsToHome() {
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("alphabet-rail").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("alphabet-rail").assertIsDisplayed()
        composeRule.onNodeWithTag("home-search").assertIsDisplayed()
        composeRule.onNodeWithTag("home-settings").performClick()
        composeRule.onNodeWithTag("settings-back").assertIsDisplayed()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("home-search").assertIsDisplayed()
        composeRule.onNodeWithTag("alphabet-rail").assertIsDisplayed()
    }

    @Test
    fun focusedSearchBackKeepsHomeVisibleAndPreservesQuery() {
        composeRule.onNodeWithTag("home-search-field").performClick()
        composeRule.onNodeWithTag("home-search-field").performTextInput("zz unlikely app")
        composeRule.onNodeWithTag("home-search-field").assertTextContains("zz unlikely app")

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("home-settings").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-back").assertDoesNotExist()
        composeRule.onNodeWithTag("home-search-field").assertTextContains("zz unlikely app")
    }

    @Test
    fun backFromEmptySearchEndsEditingAndKeepsPersistentBar() {
        composeRule.onNodeWithTag("home-search-field").performClick()
        composeRule.onNodeWithTag("home-search-field").assertIsDisplayed()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("home-search-field").assertIsDisplayed().assertIsNotFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        composeRule.onNodeWithTag("home-search").assertIsDisplayed()
        composeRule.onNodeWithTag("home-settings").assertIsDisplayed()
    }

    @Test
    fun searchQuerySurvivesSettingsAndActivityRecreation() {
        composeRule.onNodeWithTag("home-search-field").performClick()
        composeRule.onNodeWithTag("home-search-field").performTextInput("zz unlikely app")
        composeRule.onNodeWithTag("home-search-field").assertTextContains("zz unlikely app")

        composeRule.onNodeWithTag("home-settings").performClick()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag("settings-open-appearance").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-back").performClick()

        composeRule.onNodeWithTag("home-search-field").assertTextContains("zz unlikely app")
    }

    @Test
    fun warmHomeIntentReturnsExistingActivityToHome() {
        val originalIntent = composeRule.activity.intent
        try {
            composeRule.onNodeWithTag("home-search-field").performClick()
            composeRule.onNodeWithTag("home-search-field").performTextInput("zz unlikely app")
            composeRule.onNodeWithTag("home-settings").performClick()
            composeRule.onNodeWithTag("settings-back").assertIsDisplayed()
            composeRule.runOnIdle {
                val homeIntent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setClass(composeRule.activity, MainActivity::class.java)
                InstrumentationRegistry.getInstrumentation()
                    .callActivityOnNewIntent(composeRule.activity, homeIntent)
            }
            composeRule.onNodeWithTag("home-search").assertIsDisplayed()
            composeRule.onNodeWithTag("home-search-field").assertIsDisplayed().assertIsNotFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        } finally {
            // ActivityScenario matches lifecycle events using its launch intent.
            // MainActivity correctly stores HOME, so restore the harness intent
            // only after asserting the production navigation behavior.
            composeRule.runOnIdle { composeRule.activity.intent = originalIntent }
        }
    }
    @Test
    fun moduleSettingsIntentOpensTypedControlsAndSurvivesRecreation() {
        val originalIntent = composeRule.activity.intent
        try {
            composeRule.runOnIdle {
                val component = ComponentName(
                    "io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused.debug",
                    "io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused.MostUsedSortService",
                )
                val settingsIntent = Intent(ModuleContract.ACTION_OPEN_MODULE_SETTINGS)
                    .putExtra(ModuleContract.EXTRA_MODULE_COMPONENT, component.flattenToString())
                InstrumentationRegistry.getInstrumentation()
                    .callActivityOnNewIntent(composeRule.activity, settingsIntent)
            }
            composeRule.waitUntil(15_000) {
                composeRule.onAllNodesWithTag("module-setting-days").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("module-setting-days").assertIsDisplayed()
            composeRule.onNodeWithText("Use in App drawer").assertIsDisplayed()
            composeRule.runOnIdle { composeRule.activity.intent = originalIntent }
            composeRule.activityRule.scenario.recreate()
            composeRule.waitUntil(15_000) {
                composeRule.onAllNodesWithTag("module-setting-days").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("module-setting-days").assertIsDisplayed()
            composeRule.onNodeWithText("Close").performClick()
        } finally {
            composeRule.runOnIdle { composeRule.activity.intent = originalIntent }
        }
    }

}
