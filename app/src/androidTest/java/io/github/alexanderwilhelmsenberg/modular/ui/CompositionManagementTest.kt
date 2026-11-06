package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.ComponentName
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.composition.ContextCapabilityBinding
import io.github.alexanderwilhelmsenberg.modular.composition.LauncherContext
import io.github.alexanderwilhelmsenberg.modular.composition.ModuleInstance
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleDescriptor
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleDescriptorCodec
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
import org.junit.Rule
import org.junit.Test

class CompositionManagementTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun failedInstanceSaveKeepsEditorOpenAndShowsError() {
        showManagement(onSaveInstance = { Result.failure(IllegalArgumentException("Invalid configuration JSON")) })

        composeRule.onNodeWithText("Add instance").performClick()
        composeRule.onNodeWithText("Name").performTextInput("Sample")
        composeRule.onNodeWithText("Provider ID").performTextInput("sample.provider")
        composeRule.onNodeWithText("Save").performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("New instance").assertIsDisplayed()
        composeRule.onNodeWithText("Invalid configuration JSON").assertExists()
    }

    @Test
    fun referencedInstanceDeleteShowsBackendError() {
        val capability = CapabilityId("modular.apps.sort", 1)
        val instance = ModuleInstance("instance-1", "Primary", "sample.provider")
        val context = LauncherContext(
            "context-1",
            "Daily",
            listOf(ContextCapabilityBinding(capability, instance.id)),
        )
        showManagement(
            instances = listOf(instance),
            contexts = listOf(context),
            onDeleteInstance = { Result.failure(IllegalArgumentException("Instance is referenced by contexts: Daily")) },
        )

        composeRule.onNodeWithTag("delete-instance-instance-1").performScrollTo().performClick()
        composeRule.onNodeWithText("Confirm delete").performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete instance “Primary”?").assertIsDisplayed()
        composeRule.onNodeWithText("Instance is referenced by contexts: Daily").assertIsDisplayed()
    }

    @Test
    fun activeContextBindingSelectorIsLocked() {
        val capability = CapabilityId("modular.apps.sort", 1)
        val instance = ModuleInstance("instance-1", "Primary", "sample.provider")
        val context = LauncherContext(
            "context-1",
            "Daily",
            listOf(ContextCapabilityBinding(capability, instance.id)),
        )
        showManagement(
            instances = listOf(instance),
            contexts = listOf(context),
            activeContext = ActiveContextState(context = context),
        )

        composeRule.onNodeWithTag("edit-context-context-1").performScrollTo().performClick()

        composeRule.onNodeWithText("Bindings are locked while this context is active. Deactivate it to edit.").assertIsDisplayed()
        composeRule.onNodeWithTag("binding-instance-1").assertIsNotEnabled()
    }

    @Test
    fun descriptorExplainsFieldsInInstanceEditor() {
        val instance = ModuleInstance("instance-1", "Personal", "sample.provider")
        showManagement(instances = listOf(instance), descriptor = descriptor())

        composeRule.onNodeWithTag("edit-instance-instance-1").performScrollTo().performClick()

        composeRule.onNode(hasText("Sample Sort") and hasAnyAncestor(isDialog())).assertExists()
        composeRule.onNode(hasText("Controls sample sorting.") and hasAnyAncestor(isDialog())).assertExists()
        composeRule.onNode(
            hasText("Direction (direction) · string · default: \"ascending\"") and
                hasAnyAncestor(isDialog()),
        ).assertExists()
    }

    @Test
    fun activeInstanceCannotBeEdited() {
        val instance = ModuleInstance("instance-1", "Personal", "sample.provider")
        val context = LauncherContext(
            "context-1", "Daily",
            listOf(ContextCapabilityBinding(CapabilityId("modular.apps.sort", 1), instance.id)),
        )
        showManagement(
            instances = listOf(instance),
            contexts = listOf(context),
            activeContext = ActiveContextState(context = context),
        )

        composeRule.onNodeWithTag("edit-instance-instance-1").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Deactivate the active context before editing this instance.").assertExists()
    }

    private fun showManagement(
        instances: List<ModuleInstance> = emptyList(),
        contexts: List<LauncherContext> = emptyList(),
        activeContext: ActiveContextState = ActiveContextState.NONE,
        descriptor: ModuleDescriptor? = null,
        onSaveInstance: suspend (ModuleInstance) -> Result<Unit> = { Result.success(Unit) },
        onDeleteInstance: suspend (String) -> Result<Unit> = { Result.success(Unit) },
    ) {
        composeRule.setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    CompositionManagement(
                        instances = instances,
                        contexts = contexts,
                        modules = listOf(
                            ModuleRecord(
                                component = ComponentName("sample", "sample.ModuleService"),
                                moduleId = "sample.provider",
                                capabilities = listOf("modular.apps.sort/v1"),
                                descriptor = descriptor,
                            ),
                        ),
                        activeContext = activeContext,
                        onSaveInstance = onSaveInstance,
                        onDeleteInstance = onDeleteInstance,
                        onSaveContext = { Result.success(Unit) },
                        onDeleteContext = { Result.success(Unit) },
                        onActivateContext = {},
                        onDeactivateContext = {},
                    )
                }
            }
        }
    }

    private fun descriptor(): ModuleDescriptor = ModuleDescriptorCodec.parse("""
        {"schemaVersion":1,"moduleId":"sample.provider","moduleVersion":"1",
         "displayName":"Sample Sort","description":"Controls sample sorting.",
         "coreProtocol":{"min":1,"max":4},
         "providedCapabilities":["modular.apps.sort/v1"],
         "requestedCoreCapabilities":[],
         "instanceConfiguration":{"schemaVersion":1,"fields":[
           {"key":"direction","title":"Direction","type":"string","default":"ascending"}
         ]}}
    """.trimIndent())
}
