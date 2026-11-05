package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import org.junit.Rule
import org.junit.Test

class CoreNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsBackReturnsToHome() {
        composeRule.onNodeWithText("Apps").assertIsDisplayed()
        composeRule.onNodeWithTag("home-settings").performClick()
        composeRule.onNodeWithText("Back").assertIsDisplayed()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithText("Apps").assertIsDisplayed()
    }

    @Test
    fun warmHomeIntentReturnsExistingActivityToHome() {
        val originalIntent = composeRule.activity.intent
        try {
            composeRule.onNodeWithTag("home-settings").performClick()
            composeRule.onNodeWithText("Back").assertIsDisplayed()
            composeRule.runOnIdle {
                val homeIntent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setClass(composeRule.activity, MainActivity::class.java)
                InstrumentationRegistry.getInstrumentation()
                    .callActivityOnNewIntent(composeRule.activity, homeIntent)
            }
            composeRule.onNodeWithText("Apps").assertIsDisplayed()
        } finally {
            // ActivityScenario matches lifecycle events using its launch intent.
            // MainActivity correctly stores HOME, so restore the harness intent
            // only after asserting the production navigation behavior.
            composeRule.runOnIdle { composeRule.activity.intent = originalIntent }
        }
    }
}
