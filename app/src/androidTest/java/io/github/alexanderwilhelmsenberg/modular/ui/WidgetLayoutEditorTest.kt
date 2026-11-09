package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import io.github.alexanderwilhelmsenberg.modular.widgets.WidgetLayoutLimits
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePreferences
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WidgetLayoutEditorTest {
    @get:Rule val rule = createComposeRule()

    @Test fun areaChangesWriteTogetherOnlyAfterSave() {
        val writes = mutableListOf<WorkspacePreferences>()
        rule.setContent {
            var open by remember { mutableStateOf(false) }
            MaterialTheme {
                TextButton(onClick = { open = true }) { Text("Open area") }
                if (open) WidgetAreaEditor(
                    preferences = WorkspacePreferences(), busy = false, error = null,
                    onDismiss = { open = false },
                    onSave = { draft, afterSuccess -> writes.add(draft); afterSuccess() },
                )
            }
        }

        rule.onNodeWithText("Open area").performClick()
        rule.onNodeWithTag("workspace-widget-margin")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        rule.onNodeWithTag("workspace-widget-area-width")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(75f) }
        assertEquals(0, writes.size)
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithTag("workspace-widget-area-save").assertDoesNotExist()

        rule.onNodeWithText("Open area").performClick()
        rule.onNodeWithTag("workspace-widget-margin")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        rule.onNodeWithTag("workspace-widget-area-width")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(75f) }
        rule.onNodeWithTag("workspace-widget-gap")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
        rule.onNodeWithTag("workspace-widget-area-save").performClick()
        assertEquals(1, writes.size)
        assertEquals(75, writes.single().widgetAreaWidthPercent)
        assertEquals(0, writes.single().widgetMarginDp)
        assertEquals(8, writes.single().widgetGapDp)
    }

    @Test fun failedWidgetSaveKeepsHeightSpanAndPaddingDraftForRetry() {
        val widget = WorkspaceWidget(42, "home", "test/Provider", heightDp = 128)
        val writes = mutableListOf<List<Int>>()
        rule.setContent {
            var open by remember { mutableStateOf(true) }
            MaterialTheme {
                if (open) WidgetSizeEditor(
                    widget = widget,
                    preferences = WorkspacePreferences(),
                    limits = WidgetLayoutLimits(100, 64, 400, true, true),
                    canFitHalf = { _, _ -> true },
                    onDismiss = { open = false },
                    onSave = { id, height, span, padding ->
                        writes.add(listOf(id, height, span, padding))
                        if (writes.size == 1) Result.failure(IllegalStateException("Disk busy"))
                        else Result.success(Unit)
                    },
                    onSaved = { open = false },
                )
            }
        }

        rule.onNodeWithTag("workspace-widget-span-half").performClick()
        rule.onNodeWithTag("workspace-widget-height")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(176f) }
        rule.onNodeWithTag("workspace-widget-padding")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
        assertEquals(0, writes.size)
        rule.onNodeWithTag("workspace-widget-layout-save").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Disk busy").assertExists()
        rule.onNodeWithText("Widget height: 176 dp").assertExists()
        rule.onNodeWithText("8 dp").assertExists()
        rule.onNodeWithTag("workspace-widget-layout-save").performClick()
        rule.waitForIdle()
        assertEquals(listOf(42, 176, 1, 8), writes[0])
        assertEquals(writes[0], writes[1])
        rule.onNodeWithTag("workspace-widget-layout-save").assertDoesNotExist()
    }
}
