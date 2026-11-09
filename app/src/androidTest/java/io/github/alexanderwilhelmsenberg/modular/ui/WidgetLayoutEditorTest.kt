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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import io.github.alexanderwilhelmsenberg.modular.widgets.WidgetLayoutLimits
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
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

    @Test fun failedWidgetSaveKeepsGridSizeAndPaddingDraftForRetry() {
        val widget = WorkspaceWidget(42, "home", "test/Provider", heightDp = 128)
        val writes = mutableListOf<List<Int>>()
        rule.setContent {
            var open by remember { mutableStateOf(true) }
            MaterialTheme {
                if (open) WidgetSizeEditor(
                    widget = widget,
                    preferences = WorkspacePreferences(),
                    limits = WidgetLayoutLimits(100, 64, 400, true, true),
                    onDismiss = { open = false },
                    onSave = { id, columns, rows, column, row, padding ->
                        writes.add(listOf(id, columns, rows, column, row, padding))
                        if (writes.size == 1) Result.failure(IllegalStateException("Disk busy"))
                        else Result.success(Unit)
                    },
                    onSaved = { open = false },
                )
            }
        }

        rule.onNodeWithTag("workspace-widget-columns")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        rule.onNodeWithTag("workspace-widget-rows")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        rule.onNodeWithTag("workspace-widget-padding")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
        assertEquals(0, writes.size)
        rule.onNodeWithTag("workspace-widget-layout-save").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Disk busy").assertExists()
        rule.onNodeWithText("Size: 3 × 3 cells").assertExists()
        rule.onNodeWithText("8 dp").assertExists()
        rule.onNodeWithTag("workspace-widget-layout-save").performClick()
        rule.waitForIdle()
        assertEquals(listOf(42, 3, 3, -1, -1, 8), writes[0])
        assertEquals(writes[0], writes[1])
        rule.onNodeWithTag("workspace-widget-layout-save").assertDoesNotExist()
    }

    @Test fun gridSettingsSaveKeepsDraftAfterFailure() {
        val writes = mutableListOf<WorkspacePreferences>()
        rule.setContent {
            MaterialTheme {
                HomeGridSettingsUi(OrganizationSnapshot(), onUpdate = { batch ->
                    val edit = batch.edits.single() as OrganizationEdit.SetWorkspacePreferences
                    writes.add(edit.preferences)
                    if (writes.size == 1) Result.failure(IllegalStateException("Disk busy"))
                    else Result.success(OrganizationSnapshot(workspacePreferences = edit.preferences))
                })
            }
        }

        rule.onNodeWithTag("home-grid-columns")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
        rule.onNodeWithTag("home-grid-row-height")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(128f) }
        assertEquals(0, writes.size)
        rule.onNodeWithTag("home-grid-save").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Disk busy").assertExists()
        rule.onNodeWithText("128 dp").assertExists()
        rule.onNodeWithTag("home-grid-save").performClick()
        rule.waitForIdle()
        assertEquals(2, writes.size)
        assertEquals(writes[0], writes[1])
        assertEquals(6, writes[1].gridColumnCount)
        assertEquals(128, writes[1].gridRowHeightDp)
    }


    @Test fun legacyOversizedRowSpanCanBeKeptButNewOversizedSpanCannotBeSaved() {
        val widget = WorkspaceWidget(43, "home", "test/Provider", heightDp = 4000)
        val writes = mutableListOf<List<Int>>()
        rule.setContent {
            MaterialTheme {
                WidgetSizeEditor(widget, WorkspacePreferences(),
                    WidgetLayoutLimits(100, 64, 5000, true, true),
                    onDismiss = {}, onSave = { id, columns, rows, column, row, padding ->
                        writes.add(listOf(id, columns, rows, column, row, padding))
                        Result.success(Unit)
                    }, onSaved = {})
            }
        }

        rule.onNodeWithTag("workspace-widget-rows")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(34f) }
        rule.onNodeWithTag("workspace-widget-layout-save").assertIsNotEnabled()
        rule.onNodeWithTag("workspace-widget-rows")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(38f) }
        rule.onNodeWithTag("workspace-widget-padding")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
        rule.onNodeWithTag("workspace-widget-layout-save").performClick()
        rule.waitForIdle()
        assertEquals(listOf(43, 4, 38, -1, -1, 8), writes.single())
    }

}
