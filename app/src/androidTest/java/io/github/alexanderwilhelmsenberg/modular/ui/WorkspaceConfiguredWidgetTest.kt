package io.github.alexanderwilhelmsenberg.modular.ui

import android.appwidget.AppWidgetHost
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
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
    private val fixtureUri = Uri.parse("content://io.github.alexanderwilhelmsenberg.modular.debug.test.workspacefixture")

    @Test fun configureSuccessNewCancellationAndReconfigureCancellation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val fixture = instrumentation.context
        val store = OrganizationStore(target)
        val host = AppWidgetHost(target, 0x4d4f44)
        val beforeIds = host.appWidgetIds.toSet()
        val beforeWidgets = runBlocking { store.currentSnapshot().widgets }
        val provider = ComponentName(fixture, ConfiguredWorkspaceFixtureProvider::class.java).flattenToString()
        val originalChoice = fixtureState(target).getBoolean("accept_next", true)
        val startingLaunches = fixtureState(target).getInt("launches", 0)
        try {
            val shell = instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package ${target.packageName}")
            ParcelFileDescriptor.AutoCloseInputStream(shell).close()
            setFixtureChoice(target, true)
            waitForHome()
            addConfiguredFixture()
            rule.waitUntil(15_000) {
                fixtureState(target).getInt("launches", 0) > startingLaunches &&
                    runBlocking { store.currentSnapshot().widgets.count { it.provider == provider } } ==
                    beforeWidgets.count { it.provider == provider } + 1
            }
            val added = runBlocking { store.currentSnapshot().widgets.last { it.provider == provider } }
            assertEquals(added.appWidgetId, fixtureState(target).getInt("last_id", -1))
            rule.onNodeWithTag("workspace-widget:${added.appWidgetId}").assertIsDisplayed()
            val afterSuccessIds = host.appWidgetIds.toSet()

            setFixtureChoice(target, false)
            val launchesBeforeCancel = fixtureState(target).getInt("launches", 0)
            addConfiguredFixture()
            rule.waitUntil(15_000) { fixtureState(target).getInt("launches", 0) > launchesBeforeCancel }
            rule.waitUntil(15_000) { host.appWidgetIds.toSet() == afterSuccessIds }
            assertEquals(beforeWidgets.size + 1, runBlocking { store.currentSnapshot().widgets.size })

            rule.onNodeWithTag("home-settings").performClick()
            rule.onNodeWithTag("settings-open-productivity").performClick()
            rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-home-screens"))
            rule.onNodeWithTag("settings-home-screens").performClick()
            val launchesBeforeReconfigure = fixtureState(target).getInt("launches", 0)
            rule.onNodeWithText("Configure").performScrollTo().performClick()
            rule.waitUntil(15_000) { fixtureState(target).getInt("launches", 0) > launchesBeforeReconfigure }
            assertEquals(added.appWidgetId, fixtureState(target).getInt("last_id", -1))
            assertTrue(runBlocking { store.currentSnapshot().widgets.any { it.appWidgetId == added.appWidgetId } })
        } finally {
            setFixtureChoice(target, originalChoice)
            runBlocking {
                val snapshot = store.currentSnapshot()
                store.update(OrganizationBatch(listOf(OrganizationEdit.SetWidgets(beforeWidgets)),
                    snapshot.revision)).getOrThrow()
            }
            (host.appWidgetIds.toSet() - beforeIds).forEach(host::deleteAppWidgetId)
        }
    }

    private fun waitForHome() {
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("workspace-home").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun addConfiguredFixture() {
        rule.onNodeWithTag("workspace-home").performScrollToNode(hasTestTag("workspace-add-widget"))
        rule.onNodeWithTag("workspace-add-widget").performClick()
        val provider = ComponentName(InstrumentationRegistry.getInstrumentation().context,
            ConfiguredWorkspaceFixtureProvider::class.java).flattenToString()
        val tag = "widget-provider:$provider"
        rule.onNodeWithTag("widget-provider-list").performScrollToNode(hasTestTag(tag))
        rule.onNodeWithTag(tag).performClick()
    }

    private fun fixtureState(context: Context): Bundle = requireNotNull(
        context.contentResolver.call(fixtureUri, "snapshot", null, null),
    )

    private fun setFixtureChoice(context: Context, accept: Boolean) {
        requireNotNull(context.contentResolver.call(fixtureUri, "set_accept", null,
            Bundle().apply { putBoolean("accept_next", accept) }))
    }
}
