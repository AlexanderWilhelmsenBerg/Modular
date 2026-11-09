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
    private val fixtureUri = "content://io.github.alexanderwilhelmsenberg.modular.debug.test.workspacefixture"
    private data class FixtureState(val acceptNext: Boolean, val launches: Int, val lastId: Int)

    @Test fun configureSuccessNewCancellationAndReconfigureCancellation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val fixture = instrumentation.context
        val store = OrganizationStore(target)
        val host = AppWidgetHost(target, 0x4d4f44)
        val beforeIds = host.appWidgetIds.toSet()
        val beforeWidgets = runBlocking { store.currentSnapshot().widgets }
        val provider = ComponentName(fixture, ConfiguredWorkspaceFixtureProvider::class.java).flattenToString()
        val originalChoice = fixtureState().acceptNext
        val startingLaunches = fixtureState().launches
        try {
            val shell = instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package ${target.packageName}")
            ParcelFileDescriptor.AutoCloseInputStream(shell).bufferedReader().use { it.readText() }
            setFixtureChoice(true)
            waitForHome()
            addConfiguredFixture()
            rule.waitUntil(15_000) {
                fixtureState().launches > startingLaunches &&
                    runBlocking { store.currentSnapshot().widgets.count { it.provider == provider } } ==
                    beforeWidgets.count { it.provider == provider } + 1
            }
            val added = runBlocking { store.currentSnapshot().widgets.last { it.provider == provider } }
            assertEquals(added.appWidgetId, fixtureState().lastId)
            rule.onNodeWithTag("workspace-widget:${added.appWidgetId}").assertIsDisplayed()
            val afterSuccessIds = host.appWidgetIds.toSet()

            setFixtureChoice(false)
            val launchesBeforeCancel = fixtureState().launches
            addConfiguredFixture()
            rule.waitUntil(15_000) { fixtureState().launches > launchesBeforeCancel }
            rule.waitUntil(15_000) { host.appWidgetIds.toSet() == afterSuccessIds }
            assertEquals(beforeWidgets.size + 1, runBlocking { store.currentSnapshot().widgets.size })

            rule.onNodeWithTag("home-settings").performClick()
            rule.onNodeWithTag("settings-open-productivity").performClick()
            rule.onNodeWithTag("settings-open-home").performClick()
            val launchesBeforeReconfigure = fixtureState().launches
            rule.onNodeWithText("Configure").performScrollTo().assertIsDisplayed().performClick()
            rule.waitUntil(15_000) { fixtureState().launches > launchesBeforeReconfigure }
            assertEquals(added.appWidgetId, fixtureState().lastId)
            assertTrue(runBlocking { store.currentSnapshot().widgets.any { it.appWidgetId == added.appWidgetId } })
        } finally {
            setFixtureChoice(originalChoice)
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

    private fun fixtureState(): FixtureState {
        val output = fixtureCall("snapshot")
        fun field(name: String): String = requireNotNull(
            Regex("(?:^|[,{\\s])$name=([^,}\\]]+)").find(output)?.groupValues?.get(1)?.trim(),
        ) { "Missing $name in fixture response: $output" }
        val accept = field("accept_next")
        require(accept == "true" || accept == "false") { "Invalid fixture choice: $output" }
        return FixtureState(accept.toBoolean(), field("launches").toInt(), field("last_id").toInt())
    }

    private fun setFixtureChoice(accept: Boolean) {
        fixtureCall("set_accept --extra accept_next:b:$accept")
        check(fixtureState().acceptNext == accept) { "Fixture choice did not persist" }
    }

    private fun fixtureCall(methodAndArgs: String): String {
        val command = "content call --uri $fixtureUri --method $methodAndArgs"
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
        check(output.startsWith("Result: Bundle[")) { "Fixture command failed ($command): $output" }
        return output
    }
}
