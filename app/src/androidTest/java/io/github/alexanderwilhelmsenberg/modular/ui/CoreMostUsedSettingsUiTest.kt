package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CoreMostUsedSettingsUiTest {
    @get:Rule val rule = createComposeRule()

    private val first = app("First", "example.first", "FirstActivity", 1)
    private val second = app("Second", "example.second", "SecondActivity", 1)
    private val sameOne = app("Same", "example.same.one", "SameActivity", 1)
    private val sameTwo = app("Same", "example.same.two", "SameActivity", 2)
    private val catalog = listOf(first, second, sameOne, sameTwo)

    @Test fun enablingExplainsOptionalAccessAndOnlyExplicitActionOpensSettings() {
        var enabled by mutableStateOf(false)
        var accessOpens = 0
        var infoOpens = 0
        rule.setContent { Themed {
            Settings(
                enabled = enabled,
                hasUsageAccess = false,
                onSetEnabled = { enabled = it; Result.success(Unit) },
                onOpenUsageAccess = { accessOpens++ },
                onOpenAppInfo = { infoOpens++ },
            )
        } }

        rule.onNodeWithTag("core-most-used-toggle").performClick()
        rule.waitForIdle()
        assertEquals(true, enabled)
        assertEquals(0, accessOpens)
        rule.onNodeWithText("Your pinned apps work now.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Later").performClick()

        rule.onNodeWithText("Configure Most Used").performClick()
        rule.onNodeWithText("Usage Access is off. Saved pins still appear on Home; suggestions need Usage Access.")
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Usage Access blocked?").performScrollTo().performClick()
        rule.onNodeWithText("first tap Modular's disabled row", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Allow restricted settings", substring = true).assertIsDisplayed()
        assertEquals(0, infoOpens)
        rule.onNodeWithText("Open App info").performClick()
        assertEquals(1, infoOpens)
    }

    @Test fun pickerUsesExactKeyAndFailedRemovalPreservesSavedPins() {
        var pins by mutableStateOf(listOf(first.key, second.key))
        var selected: Pair<AppKey, Boolean>? = null
        var ordered: List<AppKey>? = null
        rule.setContent { Themed {
            Settings(
                pinnedKeys = pins,
                onSetPinned = { key, pinned ->
                    selected = key to pinned
                    if (pinned) {
                        pins = pins + key
                        Result.success(Unit)
                    } else Result.failure(IllegalStateException("Could not save pin"))
                },
                onReorderPins = { keys -> ordered = keys; pins = keys; Result.success(Unit) },
            )
        } }

        rule.onNodeWithText("Configure Most Used").performClick()
        rule.onAllNodesWithText("Down")[0].performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf(second.key, first.key), ordered)
        rule.onAllNodesWithText("Remove")[0].performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Could not save pin").performScrollTo().assertIsDisplayed()
        assertEquals(listOf(second.key, first.key), pins)

        rule.onNodeWithText("Pin apps").performScrollTo().performClick()
        rule.onNodeWithTag("core-most-used-picker-search").performScrollTo().performTextInput("Same")
        rule.onNodeWithText("Same · example.same.two/SameActivity · profile 2")
            .performScrollTo().assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(sameTwo.key to true, selected)
    }

    @Test fun countControlsRespectBoundsAndPersistChanges() {
        var days by mutableStateOf(1)
        var count by mutableStateOf(20)
        rule.setContent { Themed {
            Settings(
                days = days,
                suggestedCount = count,
                onSetDays = { days = it; Result.success(Unit) },
                onSetSuggestedCount = { count = it; Result.success(Unit) },
            )
        } }

        rule.onNodeWithText("Configure Most Used").performClick()
        rule.onNodeWithTag("core-most-used-history-decrease").assertIsNotEnabled()
        rule.onNodeWithTag("core-most-used-suggestions-increase").assertIsNotEnabled()
        rule.onNodeWithTag("core-most-used-history-increase").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("core-most-used-suggestions-decrease").performClick()
        rule.waitForIdle()
        assertEquals(2, days)
        assertEquals(19, count)
        rule.onNodeWithText("History: 2 days").assertIsDisplayed()
        rule.onNodeWithText("Suggestions: 19 apps").assertIsDisplayed()
    }

    @Test fun loadingDoesNotBlockToggleAndPinsOnlyNeedsNoAccessPrompt() {
        var enabled by mutableStateOf(true)
        rule.setContent { Themed {
            Settings(
                enabled = enabled,
                suggestedCount = 0,
                hasUsageAccess = false,
                loading = true,
                onSetEnabled = { enabled = it; Result.success(Unit) },
            )
        } }

        rule.onNodeWithTag("core-most-used-toggle").performClick()
        rule.waitForIdle()
        assertEquals(false, enabled)
        rule.onNodeWithTag("core-most-used-toggle").performClick()
        rule.waitForIdle()
        assertEquals(true, enabled)
        rule.onNodeWithText("Suggestions need Usage Access").assertDoesNotExist()
    }

    @Composable
    private fun Settings(
        enabled: Boolean = true,
        days: Int = 7,
        suggestedCount: Int = 8,
        pinnedKeys: List<AppKey> = emptyList(),
        hasUsageAccess: Boolean = true,
        loading: Boolean = false,
        onSetEnabled: suspend (Boolean) -> Result<Unit> = { Result.success(Unit) },
        onSetDays: suspend (Int) -> Result<Unit> = { Result.success(Unit) },
        onSetSuggestedCount: suspend (Int) -> Result<Unit> = { Result.success(Unit) },
        onSetPinned: suspend (AppKey, Boolean) -> Result<Unit> = { _, _ -> Result.success(Unit) },
        onReorderPins: suspend (List<AppKey>) -> Result<Unit> = { Result.success(Unit) },
        onOpenUsageAccess: () -> Unit = {},
        onOpenAppInfo: () -> Unit = {},
    ) {
        CoreMostUsedSettingsUi(
            enabled, days, suggestedCount, pinnedKeys, emptySet(), hasUsageAccess, catalog,
            onSetEnabled, onSetDays, onSetSuggestedCount, onSetPinned, onReorderPins,
            onOpenUsageAccess, onOpenAppInfo, onRefresh = {}, loading = loading,
        )
    }

    private fun app(label: String, packageName: String, activityName: String, profile: Long) =
        LaunchableApp(AppKey(packageName, activityName, profile), label, 0, true, null)

    @Composable private fun Themed(content: @Composable () -> Unit) { MaterialTheme { content() } }
}
