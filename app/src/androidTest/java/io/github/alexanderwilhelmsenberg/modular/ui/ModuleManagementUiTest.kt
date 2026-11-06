package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.ComponentName
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleConnectionStatus
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleManagementEvent
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
import io.github.alexanderwilhelmsenberg.modular.security.ModuleIdentity
import io.github.alexanderwilhelmsenberg.modular.security.ModuleSecurityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ModuleManagementUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val component = ComponentName("example.addon", "example.addon.Service")
    private val capability = CapabilityId("modular.apps.sort", 1)

    @Test
    fun moduleSwitchUsesNeutralLabelAndAnnouncesCurrentState() {
        composeRule.setContent {
            TestScreen {
                ModuleManagementCard(
                    module = ModuleRecord(component, moduleId = "Example", enabled = true),
                    securityStatus = null,
                    corePackageName = "core.package",
                    onSetEnabled = { _, _ -> }, onRetry = {}, onUninstall = {},
                    onSetGrant = { _, _, _ -> },
                )
                ModuleManagementCard(
                    module = ModuleRecord(
                        ComponentName("example.other", "example.other.Service"),
                        moduleId = "Other", enabled = false,
                    ),
                    securityStatus = null,
                    corePackageName = "core.package",
                    onSetEnabled = { _, _ -> }, onRetry = {}, onUninstall = {},
                    onSetGrant = { _, _, _ -> },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Example module").assertIsOn()
        composeRule.onNodeWithContentDescription("Other module").assertIsOff()
    }

    @Test
    fun retryIsDisabledWhileConnectingOrDisabled() {
        composeRule.setContent {
            TestScreen {
                ModuleManagementCard(
                    module = ModuleRecord(component, status = ModuleConnectionStatus.CONNECTING),
                    securityStatus = null,
                    corePackageName = "core.package",
                    onSetEnabled = { _, _ -> },
                    onRetry = {},
                    onUninstall = {},
                    onSetGrant = { _, _, _ -> },
                )
                ModuleManagementCard(
                    module = ModuleRecord(
                        ComponentName("example.disabled", "example.disabled.Service"),
                        enabled = false,
                        status = ModuleConnectionStatus.DISABLED,
                    ),
                    securityStatus = null,
                    corePackageName = "core.package",
                    onSetEnabled = { _, _ -> },
                    onRetry = {},
                    onUninstall = {},
                    onSetGrant = { _, _, _ -> },
                )
            }
        }
        composeRule.onAllNodesWithText("Retry connection").assertCountEquals(2)
        composeRule.onAllNodesWithText("Retry connection").assertAll(isNotEnabled())
    }

    @Test
    fun uninstallRequiresConfirmationAndCancelDoesNotInvokeCallback() {
        var uninstallCount = 0
        composeRule.setContent {
            TestScreen {
                ModuleManagementCard(
                    module = ModuleRecord(component),
                    securityStatus = null,
                    corePackageName = "core.package",
                    onSetEnabled = { _, _ -> },
                    onRetry = {},
                    onUninstall = { uninstallCount += 1 },
                    onSetGrant = { _, _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Uninstall add-on").performScrollTo().performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertEquals(0, uninstallCount) }

        composeRule.onNodeWithText("Uninstall add-on").performScrollTo().performClick()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.runOnIdle { assertEquals(1, uninstallCount) }
    }

    @Test
    fun eventLogShowsOldFailuresAndErrorsFilter() {
        val events = listOf(
            ModuleManagementEvent(10L, "example.addon", "bind", "Old connection failure", true),
            ModuleManagementEvent(20L, "example.addon", "retry", "Retry requested", false),
        )
        composeRule.setContent {
            TestScreen {
                ModuleEventLog(events = events, modules = emptyList(), onClear = {})
            }
        }

        composeRule.onNodeWithText("Module event log · 2").performClick()
        composeRule.onNodeWithText("Old connection failure").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Retry requested").assertIsDisplayed()
        composeRule.onNodeWithText("Errors").performClick()
        composeRule.onNodeWithText("Old connection failure").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Retry requested").assertDoesNotExist()
    }

    @Test
    fun eventLogFiltersWrapAtCompactWidthWithLargeText() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(240.dp)) {
                    ModuleEventLog(events = emptyList(), modules = emptyList(), onClear = {})
                }
            }
        }
        composeRule.onNodeWithText("Module event log · 0").performClick()
        val all = composeRule.onNodeWithText("All").fetchSemanticsNode().boundsInRoot
        val errors = composeRule.onNodeWithText("Errors").fetchSemanticsNode().boundsInRoot
        assertTrue("Filter buttons must not overlap at large text", errors.top >= all.bottom)
    }

    @Test
    fun requestedCapabilitySwitchHasAccessibleLabelAndChangesGrant() {
        var requestedGrant: Boolean? = null
        composeRule.setContent {
            TestScreen {
                ModuleManagementCard(
                    module = ModuleRecord(component),
                    securityStatus = ModuleSecurityStatus(
                        packageName = component.packageName,
                        identity = ModuleIdentity(component.packageName, listOf("a".repeat(64))),
                        sameSignerAsCore = false,
                        requestedCapabilities = setOf(capability),
                        explicitGrants = emptySet(),
                        effectiveGrants = emptySet(),
                        unsupportedRequests = emptySet(),
                    ),
                    corePackageName = "core.package",
                    onSetEnabled = { _, _ -> },
                    onRetry = {},
                    onUninstall = {},
                    onSetGrant = { _, selected, granted ->
                        if (selected == capability) requestedGrant = granted
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Access to ${capability.wireValue}")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(true, requestedGrant) }
    }

    @Test
    fun explicitGrantCanBeRevokedWhenNoLongerRequested() {
        var revoked: Boolean? = null
        composeRule.setContent {
            TestScreen {
                ModuleManagementCard(
                    module = ModuleRecord(component, enabled = false, status = ModuleConnectionStatus.DISABLED),
                    securityStatus = ModuleSecurityStatus(
                        packageName = component.packageName,
                        identity = null,
                        sameSignerAsCore = false,
                        requestedCapabilities = emptySet(),
                        explicitGrants = setOf(capability),
                        effectiveGrants = emptySet(),
                        unsupportedRequests = emptySet(),
                        error = "Identity unavailable",
                    ),
                    corePackageName = "core.package",
                    onSetEnabled = { _, _ -> },
                    onRetry = {},
                    onUninstall = {},
                    onSetGrant = { _, selected, granted ->
                        if (selected == capability) revoked = !granted
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Access to ${capability.wireValue}")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(true, revoked) }
    }

    @Composable
    private fun TestScreen(content: @Composable () -> Unit) {
        MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                content()
            }
        }
    }
}
