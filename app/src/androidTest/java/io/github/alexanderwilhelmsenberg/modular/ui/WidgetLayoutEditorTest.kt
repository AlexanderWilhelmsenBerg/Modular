package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import io.github.alexanderwilhelmsenberg.modular.widgets.WidgetLayoutLimits
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePreferences
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceSection
import io.github.alexanderwilhelmsenberg.modular.workspace.WidgetAreaGeometry
import io.github.alexanderwilhelmsenberg.modular.workspace.groupWidgetGridBands
import io.github.alexanderwilhelmsenberg.modular.workspace.packWidgetGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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


    @Test fun failedGridRemovalKeepsWidgetAndConfirmationForRetry() {
        val widget = WorkspaceWidget(44, "home", "test/Provider", heightDp = 96,
            columnSpan = 1, rowSpan = 1, gridColumn = 0, gridRow = 0)
        val preferences = WorkspacePreferences(showClock = false, showDate = false,
            gridRowHeightDp = 96, sectionOrder = listOf(WorkspaceSection.WIDGETS))
        var attempts = 0
        rule.setContent {
            var present by remember { mutableStateOf(true) }
            val screenDensity = LocalDensity.current
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(screenDensity.density, 2f)) {
                    WorkspaceHome(
                        pageId = "home", pageTitle = null, preferences = preferences,
                        widgets = if (present) listOf(widget) else emptyList(),
                        widgetContent = { _, _ -> Text("Widget") },
                        listeningApps = emptyList(), pinnedKeys = emptySet(),
                        listState = rememberLazyListState(), wallpaperMode = false,
                        showIcons = false, onLaunch = {}, onPinApp = null,
                        onAppInfo = null, onUninstallApp = null, onRenameApp = null,
                        onHideApp = null, onOrganizeApp = null, onAddWidget = {},
                        onEditWorkspace = {},
                        widgetLayoutLimits = { WidgetLayoutLimits(0, 0, 2048, true, true) },
                        onRemoveWidget = {
                            attempts++
                            if (attempts == 1) Result.failure(IllegalStateException("Disk busy"))
                            else { present = false; Result.success(Unit) }
                        },
                    )
                }
            }
        }

        rule.onNodeWithTag("workspace-empty-edit-space").performTouchInput { longClick() }
        rule.onNodeWithTag("workspace-menu-edit").performClick()
        rule.onNodeWithTag("workspace-widget-move:44").performClick()
        val headerBounds = rule.onNodeWithTag("workspace-widget-edit-header").fetchSemanticsNode().boundsInRoot
        val buttonBounds = rule.onNodeWithTag("workspace-widget-remove:44").fetchSemanticsNode().boundsInRoot
        assertTrue("Remove control must fit within the sticky edit header at large font",
            buttonBounds.top >= headerBounds.top && buttonBounds.bottom <= headerBounds.bottom)
        rule.onNodeWithTag("workspace-widget-remove:44").performClick()
        rule.onNodeWithTag("workspace-widget-remove-confirm").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("workspace-widget-cell:44").assertExists()
        rule.onNodeWithTag("workspace-widget-remove-confirm").assertExists()
        rule.onNodeWithTag("workspace-widget-remove-error").assertExists()
        rule.onNodeWithText("Disk busy").assertExists()
        assertEquals(1, attempts)

        rule.onNodeWithTag("workspace-widget-remove-confirm").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("workspace-widget-cell:44").assertDoesNotExist()
        assertEquals(2, attempts)
    }

    @Test fun tinyGridCellLeavesBodyCenterFreeAndRemovalOutsideCell() {
        val widget = WorkspaceWidget(45, "home", "test/Provider", heightDp = 32,
            columnSpan = 1, rowSpan = 1, gridColumn = 0, gridRow = 0)
        val preferences = WorkspacePreferences(gridColumnCount = 12,
            gridRowHeightDp = 32, widgetGapDp = 0)
        val geometry = WidgetAreaGeometry(0, 384, 0, 192)
        val layout = packWidgetGrid(listOf(widget), preferences, geometry)
        val band = groupWidgetGridBands(layout).single()
        rule.setContent {
            MaterialTheme {
                Box(androidx.compose.ui.Modifier.width(384.dp)) {
                    WorkspaceWidgetGrid(layout, band, preferences, geometry,
                        editMode = true, selectedId = 45, onSelect = {},
                        onLongPress = {}, onEmptyLongPress = {},
                        limits = { WidgetLayoutLimits(0, 0, 2048, true, true) },
                        onSave = { _, _, _, _, _ -> Result.success(Unit) },
                        widgetContent = { _, _ -> Text("Widget") })
                }
            }
        }

        val cell = rule.onNodeWithTag("workspace-widget-cell:45").fetchSemanticsNode().boundsInRoot
        val body = rule.onNodeWithTag("workspace-widget-move:45").fetchSemanticsNode().boundsInRoot
        val grip = rule.onNodeWithTag("workspace-widget-resize:45").fetchSemanticsNode().boundsInRoot
        assertTrue("Resize grip must use at most half of a tiny cell's width",
            grip.width <= cell.width / 2f + 1f)
        assertTrue("Resize grip must use at most half of a tiny cell's height",
            grip.height <= cell.height / 2f + 1f)
        assertTrue("Body center must remain outside the grip",
            body.center.y < grip.top || body.center.x < grip.left)
        rule.onNodeWithTag("workspace-widget-remove:45").assertDoesNotExist()
    }

}
