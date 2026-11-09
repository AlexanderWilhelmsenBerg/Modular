package io.github.alexanderwilhelmsenberg.modular.ui

import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceSection
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WorkspaceOrderSettingsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sectionDragWaitsForSaveAndFailedSaveRetainsDraftForRetry() {
        val writes = mutableListOf<OrganizationBatch>()
        rule.setContent {
            var snapshot by remember { mutableStateOf(OrganizationSnapshot()) }
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    WorkspaceSettingsUi(snapshot, onUpdate = { batch ->
                        writes.add(batch)
                        if (writes.size == 1) Result.failure(IllegalStateException("Disk busy"))
                        else {
                            val edit = batch.edits.single() as OrganizationEdit.SetWorkspacePreferences
                            snapshot = snapshot.copy(revision = snapshot.revision + 1,
                                workspacePreferences = edit.preferences)
                            Result.success(snapshot)
                        }
                    }, inline = true, showEntry = false)
                }
            }
        }

        val tile = rule.onNodeWithTag("workspace-section-tile:LISTENING")
        tile.performScrollTo()
        val height = tile.fetchSemanticsNode().boundsInRoot.height
        tile.performTouchInput {
            val start = center
            down(start)
            advanceEventTime(ViewConfiguration.getLongPressTimeout() + 100L)
            for (step in 1..10) moveTo(start + Offset(0f, -height * 1.35f * step / 10f), 20L)
            up()
        }
        assertEquals(0, writes.size)
        rule.onNodeWithTag("workspace-section-order-save").performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("workspace-section-order-error").performScrollTo()
        rule.onNodeWithText("Disk busy").assertExists()
        rule.onNodeWithTag("workspace-section-order-save").assertIsEnabled()
        rule.onNodeWithTag("workspace-section-order-save").performClick()
        rule.waitForIdle()

        val expected = listOf(WorkspaceSection.CLOCK, WorkspaceSection.LISTENING,
            WorkspaceSection.MOST_USED, WorkspaceSection.WIDGETS)
        assertEquals(2, writes.size)
        assertEquals(0L, writes[0].expectedRevision)
        assertEquals(expected, (writes[0].edits.single() as OrganizationEdit.SetWorkspacePreferences)
            .preferences.sectionOrder)
        assertEquals(expected, (writes[1].edits.single() as OrganizationEdit.SetWorkspacePreferences)
            .preferences.sectionOrder)
        rule.onNodeWithTag("workspace-section-order-save").assertIsNotEnabled()
    }

    @Test fun widgetOrderSavesOnePageReflowOnlyAfterExplicitSave() {
        val first = WorkspaceWidget(101, "home", "test/First", heightDp = 96,
            columnSpan = 1, rowSpan = 1, gridColumn = 0, gridRow = 0)
        val second = WorkspaceWidget(102, "home", "test/Second", heightDp = 96,
            columnSpan = 1, rowSpan = 1, gridColumn = 1, gridRow = 0)
        val otherPage = WorkspaceWidget(103, "widget:notes", "test/Other", heightDp = 96,
            columnSpan = 1, rowSpan = 1, gridColumn = 3, gridRow = 7)
        val initial = OrganizationSnapshot(widgets = listOf(first, otherPage, second))
        val writes = mutableListOf<OrganizationBatch>()
        rule.setContent {
            var snapshot by remember { mutableStateOf(initial) }
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    WorkspaceSettingsUi(snapshot, onUpdate = { batch ->
                        writes.add(batch)
                        if (writes.size == 1) Result.failure(IllegalStateException("Write failed"))
                        else {
                            val edit = batch.edits.single() as OrganizationEdit.SetWidgets
                            snapshot = snapshot.copy(revision = snapshot.revision + 1, widgets = edit.widgets)
                            Result.success(snapshot)
                        }
                    }, widgetLabel = { "Widget ${it.appWidgetId}" },
                        inline = true, showEntry = false)
                }
            }
        }

        rule.onNodeWithTag("workspace-widget-order-tile-options:102").performScrollTo().performClick()
        rule.onNodeWithText("Move up").performClick()
        assertEquals(0, writes.size)
        rule.onNodeWithTag("workspace-widget-order-save").performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("workspace-widget-order-error").performScrollTo()
        rule.onNodeWithText("Write failed").assertExists()
        rule.onNodeWithTag("workspace-widget-order-save").assertIsEnabled().performClick()
        rule.waitForIdle()

        val saved = (writes.last().edits.single() as OrganizationEdit.SetWidgets).widgets
        assertEquals(listOf(102, 103, 101), saved.map { it.appWidgetId })
        assertEquals(otherPage, saved[1])
        assertEquals(-1, saved[0].gridColumn)
        assertEquals(-1, saved[0].gridRow)
        assertEquals(-1, saved[2].gridColumn)
        assertEquals(-1, saved[2].gridRow)
        assertEquals(2, writes.size)
        rule.onNodeWithTag("workspace-widget-order-save").assertIsNotEnabled()
    }
}
