package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppActionsSheetTest {
    @get:Rule val rule = createComposeRule()

    private val app = LaunchableApp(
        key = AppKey("example.app", "example.app.MainActivity", 0),
        label = "Example",
        firstInstallTime = 0,
        enabled = true,
        loadingProgress = null,
    )

    @Test fun appWithoutPinProviderStillOffersManagementActions() {
        var launches = 0
        val actions = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                HomeAppRow(
                    app = app,
                    enabled = true,
                    onAppInfo = { actions += "info:${it.key.stableId}" },
                    onUninstallApp = { actions += "uninstall:${it.key.stableId}" },
                    onClick = { launches++ },
                )
            }
        }

        rule.onNodeWithTag("app-row:${app.key.stableId}").performTouchInput { longClick() }
        rule.onNodeWithTag("app-actions-sheet").assertIsDisplayed()
        rule.onNodeWithText("example.app").assertIsDisplayed()
        rule.onNodeWithTag("app-action:pin").assertDoesNotExist()
        rule.onNodeWithTag("app-action:info").performClick()
        rule.waitForIdle()
        assertEquals(listOf("info:${app.key.stableId}"), actions)
        assertEquals(0, launches)
        rule.onNodeWithTag("app-actions-sheet").assertDoesNotExist()

        rule.onNodeWithTag("app-row:${app.key.stableId}").performTouchInput { longClick() }
        rule.onNodeWithTag("app-action:uninstall").performClick()
        rule.waitForIdle()
        assertEquals(listOf("info:${app.key.stableId}", "uninstall:${app.key.stableId}"), actions)
    }

    @Test fun pinAndOptionalActionsAppearOnlyWhenProvided() {
        val actions = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                HomeAppRow(
                    app = app,
                    enabled = true,
                    pinned = true,
                    onPinApp = { selected, newState -> actions += "pin:${selected.key.stableId}:$newState" },
                    onRenameApp = { actions += "rename:${it.key.stableId}" },
                    onHideApp = { actions += "hide:${it.key.stableId}" },
                    onClick = {},
                )
            }
        }

        rule.onNodeWithTag("app-row:${app.key.stableId}").performTouchInput { longClick() }
        rule.onNodeWithText("Unpin from starred apps").assertIsDisplayed()
        rule.onNodeWithTag("app-action:rename").assertIsDisplayed()
        rule.onNodeWithTag("app-action:hide").assertIsDisplayed()
        rule.onNodeWithTag("app-action:info").assertDoesNotExist()
        rule.onNodeWithTag("app-action:pin").performClick()
        rule.runOnIdle { assertEquals(listOf("pin:${app.key.stableId}:false"), actions) }
    }

    @Test fun unavailableAppCannotLaunchButCanOpenActions() {
        var launches = 0
        var infoOpens = 0
        rule.setContent {
            MaterialTheme {
                HomeAppRow(
                    app = app,
                    enabled = false,
                    unavailabilityReason = "Profile paused",
                    onAppInfo = { infoOpens++ },
                    onClick = { launches++ },
                )
            }
        }

        val row = rule.onNodeWithTag("app-row:${app.key.stableId}")
        row.assertIsNotEnabled()
        row.performTouchInput { click() }
        row.performTouchInput { longClick() }
        rule.onNodeWithTag("app-action:info").performClick()
        rule.runOnIdle {
            assertEquals(0, launches)
            assertEquals(1, infoOpens)
        }
    }
}
