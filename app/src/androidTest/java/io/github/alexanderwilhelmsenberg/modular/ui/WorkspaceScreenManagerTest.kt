package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import io.github.alexanderwilhelmsenberg.modular.organization.HomePageId
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WorkspaceScreenManagerTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun addWidgetPageOnLeftAndDeleteItThroughSettings() {
        val store = OrganizationStore(rule.activity)
        val name = "Validation ${System.nanoTime()}"
        var createdId: String? = null
        try {
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("home-settings").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("home-settings").performClick()
            rule.onNodeWithTag("settings-open-productivity").performClick()
            rule.onNodeWithTag("settings-open-home").performClick()
            rule.onNodeWithTag("workspace-settings-content").assertIsDisplayed()
            rule.onNodeWithTag("workspace-open-grid-settings").performScrollTo().performClick()
            rule.onNodeWithTag("home-grid-columns").assertIsDisplayed()
            rule.onNodeWithTag("settings-back").performClick()
            rule.onNodeWithTag("workspace-settings-content").assertIsDisplayed()
            rule.onNodeWithTag("workspace-tile:home").assertIsDisplayed()
            assertTrue(rule.onAllNodesWithTag("workspace-tile:category:games")
                .fetchSemanticsNodes().isEmpty())
            rule.onNodeWithTag("workspace-add-page").performScrollTo().assertIsDisplayed().performClick()
            rule.onNodeWithTag("workspace-page-name").performTextInput(name)
            rule.onNodeWithText("Left").performClick()
            rule.onNodeWithText("Save").performClick()
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("workspace-page-name").fetchSemanticsNodes().isEmpty()
            }

            rule.waitUntil(15_000) {
                runBlocking { store.currentSnapshot().workspacePages.any { it.name == name } }
            }
            val created = runBlocking { store.currentSnapshot() }
            val page = created.workspacePages.single { it.name == name }
            createdId = page.id
            assertTrue(created.pageOrder.indexOf(HomePageId.Widget(page.id)) < created.pageOrder.indexOf(HomePageId.Home))
            rule.onNodeWithTag("workspace-page-order").performScrollTo()
            rule.onNodeWithTag("workspace-tile:widget:${page.id}").performScrollTo()
            rule.onNodeWithTag("workspace-tile:widget:${page.id}").assertIsDisplayed()

            rule.onNodeWithText("Delete").performScrollTo().assertIsDisplayed().performClick()
            rule.onNodeWithText("Delete screen").assertIsDisplayed().performClick()
            rule.waitUntil(15_000) {
                runBlocking { store.currentSnapshot().workspacePages.none { it.id == page.id } }
            }
        } finally {
            createdId?.let { id ->
                runBlocking {
                    val snapshot = store.currentSnapshot()
                    if (snapshot.workspacePages.any { it.id == id })
                        store.update(OrganizationBatch(listOf(OrganizationEdit.DeleteWorkspacePage(id)))).getOrThrow()
                }
            }
        }
    }
}
