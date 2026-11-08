package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.organization.FolderDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.FolderPlacement
import io.github.alexanderwilhelmsenberg.modular.organization.FolderVisibilityMode
import io.github.alexanderwilhelmsenberg.modular.organization.HomePinRef
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppOrganizationPickerTest {
    @get:Rule val rule = createComposeRule()
    private val app = LaunchableApp(AppKey("test.app", "Main", 0), "Example", 0, true, null)

    @Test fun cancelDiscardsStagedCreation() {
        var writes = 0
        rule.setContent {
            MaterialTheme {
                AppOrganizationPicker(app, OrganizationSnapshot(), false, { _, _ -> false },
                    onUpdate = { writes++; Result.success(OrganizationSnapshot()) }, onDismiss = {})
            }
        }
        rule.onNodeWithText("Create folder").performClick()
        rule.onNodeWithTag("organization-create-name").performTextInput("Travel")
        rule.onNodeWithText("Create", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Travel · Folder · new").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun failedSaveRetainsDraftAndRetryCommitsInitialMemberAtomically() {
        var attempts = 0
        var committed: OrganizationBatch? = null
        rule.setContent {
            MaterialTheme {
                AppOrganizationPicker(app, OrganizationSnapshot(), false, { _, _ -> false },
                    onUpdate = { batch ->
                        attempts++
                        if (attempts == 1) Result.failure(IllegalStateException("Storage unavailable"))
                        else { committed = batch; Result.success(OrganizationSnapshot()) }
                    }, onDismiss = {})
            }
        }
        rule.onNodeWithText("Create folder").performClick()
        rule.onNodeWithTag("organization-create-name").performTextInput("Travel")
        rule.onNodeWithText("Create", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("organization-done").performClick()
        rule.onNodeWithTag("organization-save-error").assertIsDisplayed()
        rule.onNodeWithText("Travel · Folder · new").assertIsDisplayed()
        rule.onNodeWithTag("organization-done").performClick()
        rule.runOnIdle {
            assertEquals(2, attempts)
            val folder = (committed!!.edits.single() as OrganizationEdit.CreateFolder).folder
            assertEquals("Travel", folder.name)
            assertEquals(listOf(app.key), folder.members)
        }
    }

    @Test fun hiddenFolderStaysInManagerAndPreview() {
        val folder = FolderDefinition("hidden-folder", "Private", listOf(app.key),
            FolderPlacement.APP_LIST, FolderVisibilityMode.HIDDEN)
        val snapshot = OrganizationSnapshot(folders = listOf(folder))
        rule.setContent {
            MaterialTheme {
                ManageFoldersUi(snapshot, listOf(app), false,
                    onUpdate = { Result.success(snapshot) })
            }
        }
        rule.onNodeWithTag("manage-folder:hidden-folder").assertIsDisplayed()
        rule.onNodeWithText("Manually hidden", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("preview-folder:hidden-folder").performClick()
        rule.onNodeWithText("Example").assertIsDisplayed()
    }

    @Test fun movingFolderPinKeepsAppPinOrder() {
        val otherKey = AppKey("other.app", "Main", 0)
        val folder = FolderDefinition("folder", "Tools", placement = FolderPlacement.MOST_USED)
        val snapshot = OrganizationSnapshot(folders = listOf(folder), pins = listOf(
            HomePinRef.App(app.key), HomePinRef.Folder(folder.id), HomePinRef.App(otherKey)))
        var batch: OrganizationBatch? = null
        rule.setContent {
            MaterialTheme {
                ManageFoldersUi(snapshot, listOf(app), true,
                    onUpdate = { request -> batch = request; Result.success(snapshot) })
            }
        }
        rule.onNodeWithTag("folder-pin-up:folder").performClick()
        rule.runOnIdle {
            assertEquals(listOf(HomePinRef.Folder("folder"), HomePinRef.App(app.key),
                HomePinRef.App(otherKey)),
                (batch!!.edits.single() as OrganizationEdit.SetPins).order)
        }
    }
}
