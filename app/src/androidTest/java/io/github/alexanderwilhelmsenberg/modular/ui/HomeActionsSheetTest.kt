package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePreferences
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeActionsSheetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun widgetAndEmptyLongPressUseHomeSheetAndKeepWidgetEditTarget() {
        val widget = WorkspaceWidget(42, "home", "test/Provider", heightDp = 96,
            columnSpan = 4, rowSpan = 1, gridColumn = 0, gridRow = 0)
        var addCalls = 0
        var advancedCalls = 0
        rule.setContent {
            MaterialTheme {
                WorkspaceHome(
                    pageId = "home", pageTitle = null,
                    preferences = WorkspacePreferences(showClock = false, showDate = false),
                    widgets = listOf(widget),
                    widgetContent = { _, onLongPress ->
                        TextButton(onClick = onLongPress, modifier = Modifier.testTag("fake-widget-hold")) {
                            Text("Hold widget")
                        }
                    },
                    listeningApps = emptyList(), pinnedKeys = emptySet(),
                    listState = rememberLazyListState(), wallpaperMode = false, showIcons = false,
                    onLaunch = {}, onPinApp = null, onAppInfo = null, onUninstallApp = null,
                    onRenameApp = null, onHideApp = null, onOrganizeApp = null,
                    onAddWidget = { addCalls++ }, onEditWorkspace = {},
                    onOpenWorkspaceAdvanced = { advancedCalls++ },
                )
            }
        }

        rule.onNodeWithTag("workspace-add-widget").assertDoesNotExist()
        rule.onNodeWithTag("workspace-edit").assertDoesNotExist()
        rule.onNodeWithTag("fake-widget-hold").performClick()
        rule.onNodeWithTag("workspace-home-menu").assertIsDisplayed()
        rule.onNodeWithText("Home screen").assertIsDisplayed()
        rule.onNodeWithTag("workspace-menu-edit").performClick()
        rule.onNodeWithTag("workspace-widget-move:42").assertIsDisplayed()
        rule.onNodeWithTag("workspace-widget-edit-done").performClick()

        rule.onNodeWithTag("workspace-empty-edit-space").performTouchInput { longClick() }
        rule.onNodeWithTag("workspace-menu-advanced").performClick()
        assertEquals(1, advancedCalls)
        rule.onNodeWithTag("workspace-empty-edit-space").performTouchInput { longClick() }
        rule.onNodeWithTag("workspace-menu-add-widget").performClick()
        assertEquals(1, addCalls)
    }
}
