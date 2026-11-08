package io.github.alexanderwilhelmsenberg.modular.ui

import android.appwidget.AppWidgetHost
import android.content.ComponentName
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises provider-owned configuration through the real picker and host trampoline. */
class WorkspaceConfiguredWidgetTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun configureSuccessNewCancellationAndReconfigureCancellation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val fixture = instrumentation.context
        val store = OrganizationStore(target)
        val host = AppWidgetHost(target, 0x4d4f44)
        val beforeIds = host.appWidgetIds.toSet()
        val beforeWidgets = runBlocking { store.currentSnapshot().widgets }
        val provider = ComponentName(fixture, ConfiguredWorkspaceFixtureProvider::class.java).flattenToString()
        val preferences = fixture.getSharedPreferences("workspace_fixture_config", android.content.Context.MODE_PRIVATE)
        val originalChoice = preferences.getBoolean("accept_next", true)
        val startingLaunches = preferences.getInt("launches", 0)
        try {
            val shell = instrumentation.uiAutomation.executeShellCommand("appwidget grantbind ${target.packageName}")
            ParcelFileDescriptor.AutoCloseInputStream(shell).close()
            preferences.edit().putBoolean("accept_next", true).commit()
            waitForHome()
            addConfiguredFixture()
            rule.waitUntil(15_000) {
                preferences.getInt("launches", 0) > startingLaunches &&
                    runBlocking { store.currentSnapshot().widgets.count { it.provider == provider } } ==
                    beforeWidgets.count { it.provider == provider } + 1
            }
            val added = runBlocking { store.currentSnapshot().widgets.last { it.provider == provider } }
            assertEquals(added.appWidgetId, preferences.getInt("last_id", -1))
            rule.onNodeWithTag("workspace-widget:${added.appWidgetId}").assertIsDisplayed()
            val afterSuccessIds = host.appWidgetIds.toSet()

            preferences.edit().putBoolean("accept_next", false).commit()
            val launchesBeforeCancel = preferences.getInt("launches", 0)
            addConfiguredFixture()
            rule.waitUntil(15_000) { preferences.getInt("launches", 0) > launchesBeforeCancel }
            rule.waitUntil(15_000) { host.appWidgetIds.toSet() == afterSuccessIds }
            assertEquals(beforeWidgets.size + 1, runBlocking { store.currentSnapshot().widgets.size })

            rule.onNodeWithTag("home-settings").performClick()
            rule.onNodeWithTag("settings-open-productivity").performClick()
            rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-home-screens"))
            rule.onNodeWithTag("settings-home-screens").performClick()
            val launchesBeforeReconfigure = preferences.getInt("launches", 0)
            rule.onNodeWithText("Configure").performScrollTo().performClick()
            rule.waitUntil(15_000) { preferences.getInt("launches", 0) > launchesBeforeReconfigure }
            assertEquals(added.appWidgetId, preferences.getInt("last_id", -1))
            assertTrue(runBlocking { store.currentSnapshot().widgets.any { it.appWidgetId == added.appWidgetId } })
        } finally {
            preferences.edit().putBoolean("accept_next", originalChoice).commit()
            runBlocking { store.update(OrganizationBatch(listOf(OrganizationEdit.SetWidgets(beforeWidgets)))).getOrThrow() }
            (host.appWidgetIds.toSet() - beforeIds).forEach(host::deleteAppWidgetId)
        }
    }

    private fun waitForHome() {
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("workspace-home").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun addConfiguredFixture() {
        rule.onNodeWithTag("workspace-home").performScrollToNode(hasTestTag("workspace-add-widget"))
        rule.onNodeWithTag("workspace-add-widget").performClick()
        rule.onNodeWithText("Add Configured workspace fixture").performClick()
    }
}
