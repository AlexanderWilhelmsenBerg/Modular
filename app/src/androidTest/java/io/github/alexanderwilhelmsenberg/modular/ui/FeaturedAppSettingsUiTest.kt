package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.composition.ModuleInstance
import io.github.alexanderwilhelmsenberg.modular.composition.ResolvedContextBinding
import io.github.alexanderwilhelmsenberg.modular.home.HomeFeaturedState
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsResultParcel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FeaturedAppSettingsUiTest {
    @get:Rule val rule = createComposeRule()

    private val first = app("First", "example.first", "FirstActivity", 1)
    private val second = app("Second", "example.second", "SecondActivity", 1)
    private val normal = app("Normal", "example.normal", "NormalActivity", 1)
    private val sameOne = app("Same", "example.same.one", "SameActivity", 1)
    private val sameTwo = app("Same", "example.same.two", "SameActivity", 2)
    private val catalog = listOf(first, second, normal, sameOne, sameTwo)
    private val route = CapabilityRoute(
        StandardCapabilities.AppsFeatured, CapabilityProviderKind.MODULE, "example.provider",
    )
    private val binding = ResolvedContextBinding(
        StandardCapabilities.AppsFeatured, ModuleInstance("one", "One", route.providerId), route,
    )

    @Test fun pickerUsesSimpleNamesAndPinsTheExactKey() {
        var featured by mutableStateOf(state(listOf(first.key)))
        var saved: Pair<AppKey, Boolean>? = null
        rule.setContent { Themed {
            FeaturedAppSettingsUi(catalog, featured,
                onSetPinned = { key, pinned ->
                    saved = key to pinned
                    featured = state(featured.pinnedKeys + key)
                    Result.success(Unit)
                },
                onReorderPins = { Result.success(Unit) },
                onRefresh = {},
            )
        } }

        rule.onNodeWithText("Pin apps").performClick()
        rule.onNodeWithText("Normal").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(normal.key to true, saved)
        rule.onNodeWithText("Normal").assertIsDisplayed()
        rule.onNodeWithText("example.normal", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Find an app").performTextInput("Same")
        rule.onNodeWithText("Same · example.same.one/SameActivity · profile 1").assertIsDisplayed()
        rule.onNodeWithText("Same · example.same.two/SameActivity · profile 2").assertIsDisplayed()
    }

    @Test fun reorderUsesSavedKeysAndFailedRemovalKeepsPins() {
        var featured by mutableStateOf(state(listOf(first.key, second.key)))
        var ordered: List<AppKey>? = null
        rule.setContent { Themed {
            FeaturedAppSettingsUi(catalog, featured,
                onSetPinned = { _, _ -> Result.failure(IllegalStateException("Could not save pin")) },
                onReorderPins = { keys ->
                    ordered = keys
                    featured = state(keys)
                    Result.success(Unit)
                },
                onRefresh = {},
            )
        } }

        rule.onAllNodesWithText("Down")[0].performClick()
        rule.waitForIdle()
        assertEquals(listOf(second.key, first.key), ordered)
        rule.onAllNodesWithText("Remove")[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Could not save pin").assertIsDisplayed()
        assertEquals(listOf(second.key, first.key), featured.pinnedKeys)
    }

    private fun state(keys: List<AppKey>) = HomeFeaturedState(
        binding = binding,
        pinnedApps = keys.mapNotNull { key -> catalog.firstOrNull { it.key == key } },
        suggestedApps = emptyList(),
        pinnedKeys = keys,
        unavailablePinnedKeys = emptyList(),
        featureState = FeaturedAppsResultParcel.STATE_READY,
        featureRevision = 1,
    )

    private fun app(label: String, packageName: String, activityName: String, profile: Long) =
        LaunchableApp(AppKey(packageName, activityName, profile), label, 0, true, null)

    @Composable private fun Themed(content: @Composable () -> Unit) {
        MaterialTheme { content() }
    }
}
