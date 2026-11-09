package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.ComponentName
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import io.github.alexanderwilhelmsenberg.modular.moduleapi.InstanceConfigurationField
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleDescriptor
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleConnectionStatus
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ModuleSettingsUiTest {
    @get:Rule val rule = createComposeRule()

    private fun module(
        field: InstanceConfigurationField? = null,
        status: ModuleConnectionStatus = ModuleConnectionStatus.CONNECTED,
        sort: Boolean = true,
        managementActivity: String? = null,
    ) = ModuleRecord(
        component = ComponentName("example.module", "example.module.Service"),
        moduleId = "example.module",
        status = status,
        descriptor = ModuleDescriptor(
            schemaVersion = 1,
            moduleId = "example.module",
            moduleVersion = "1.0",
            displayName = "Example Sort",
            description = "Choose how apps appear on Home.",
            coreProtocolMin = 4,
            coreProtocolMax = 4,
            providedCapabilities = if (sort) listOf("modular.apps.sort/v1") else listOf("example.other/v1"),
            requestedCoreCapabilities = emptyList(),
            managementActivity = managementActivity,
            configurationFields = listOfNotNull(field),
        ),
    )

    private val reverse = InstanceConfigurationField(
        key = "reverse", title = "Reverse alphabetical order", type = "boolean",
        defaultValue = JsonPrimitive(false), enumValues = emptyList(),
        min = null, max = null, minLength = null, maxLength = null,
    )
    private val days = InstanceConfigurationField(
        key = "days", title = "Recent days", type = "integer",
        defaultValue = JsonPrimitive(7), enumValues = emptyList(),
        min = 1, max = 30, minLength = null, maxLength = null,
    )

    @Test fun settingsButtonOpensForOfflineModule() {
        var opened: ModuleRecord? = null
        val target = module(status = ModuleConnectionStatus.DISCONNECTED)
        rule.setContent { Themed {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ModuleManagementCard(target, null, "core", { _, _ -> }, {}, {}, { _, _, _ -> }, { opened = it })
            }
        } }
        rule.onNodeWithContentDescription("Example Sort settings").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(target, opened) }
    }

    @Test fun booleanSwitchSavesTypedConfigurationAndActivatesHomeOrder() {
        var saved: Pair<String, Boolean>? = null
        rule.setContent { Themed {
            ModuleSettingsDialog(module(reverse), "{}", false, { json, active ->
                saved = json to active
                Result.success(Unit)
            }, {})
        } }
        rule.onNodeWithContentDescription("Reverse alphabetical order").assertIsOff().performClick().assertIsOn()
        rule.onNodeWithText("Use in App drawer").performClick()
        rule.waitForIdle()
        assertEquals("{\"reverse\":true}" to true, saved)
    }

    @Test fun invalidIntegerShowsRangeAndDoesNotSave() {
        var saved: Pair<String, Boolean>? = null
        rule.setContent { Themed {
            ModuleSettingsDialog(module(days), "{}", false, { json, active ->
                saved = json to active
                Result.success(Unit)
            }, {})
        } }
        rule.onNodeWithTag("module-setting-days").performTextClearance()
        rule.onNodeWithTag("module-setting-days").performTextInput("31")
        rule.onNodeWithText("Use in App drawer").performClick()
        rule.onNodeWithText("Enter 1–30.").assertIsDisplayed()
        rule.runOnIdle { assertNull(saved) }
    }

    @Test fun activeHomeOrderCanReturnToAlphabetical() {
        var saved: Pair<String, Boolean>? = null
        rule.setContent { Themed {
            ModuleSettingsDialog(module(reverse), "{\"reverse\":true}", true, { json, active ->
                saved = json to active
                Result.success(Unit)
            }, {})
        } }
        rule.onNodeWithText("Use alphabetical order").performClick()
        rule.waitForIdle()
        assertEquals("{\"reverse\":true}" to false, saved)
    }

    @Test fun unavailableModuleExplainsStatusAndDisablesSave() {
        rule.setContent { Themed {
            ModuleSettingsDialog(module(days, status = ModuleConnectionStatus.DISCONNECTED), "{}", false, { _, _ ->
                Result.success(Unit)
            }, {})
        } }
        rule.onNodeWithText("This module is offline. Reconnect it to change settings.").assertIsDisplayed()
        rule.onNodeWithText("Use in App drawer").assertIsNotEnabled()
    }

    @Test fun offlineModuleCanOpenAddonPermissions() {
        var opened: ModuleRecord? = null
        val target = module(
            days,
            status = ModuleConnectionStatus.DISCONNECTED,
            managementActivity = ".UsageAccessActivity",
        )
        rule.setContent { Themed {
            ModuleSettingsDialog(target, "{}", false, { _, _ -> Result.success(Unit) }, {},
                onOpenAddonSettings = { opened = it })
        } }
        rule.onNodeWithText("Permissions and settings").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(target, opened) }
    }

    @Test fun nonSortModuleOnlySavesSettings() {
        rule.setContent { Themed {
            ModuleSettingsDialog(module(days, sort = false), "{}", false, { _, _ -> Result.success(Unit) }, {})
        } }
        rule.onNodeWithText("Save settings").assertIsDisplayed()
        rule.onNodeWithText("Use in App drawer").assertDoesNotExist()
    }

    @Test fun missingDescriptorExplainsWhyThereAreNoControls() {
        val target = ModuleRecord(
            component = ComponentName("example.legacy", "example.legacy.Service"),
            moduleId = "example.legacy",
            status = ModuleConnectionStatus.CONNECTED,
        )
        rule.setContent { Themed {
            ModuleSettingsDialog(target, "{}", false, { _, _ -> Result.success(Unit) }, {})
        } }
        rule.onNodeWithText("This module does not provide settings to Modular.").assertIsDisplayed()
        rule.onNodeWithTag("module-settings-save").assertDoesNotExist()
    }

    @Composable private fun Themed(content: @Composable () -> Unit) { MaterialTheme { content() } }
}
