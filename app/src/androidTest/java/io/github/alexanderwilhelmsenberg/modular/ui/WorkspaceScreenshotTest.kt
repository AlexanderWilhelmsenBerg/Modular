package io.github.alexanderwilhelmsenberg.modular.ui

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import io.github.alexanderwilhelmsenberg.modular.appearance.AppearanceSettingsStore
import io.github.alexanderwilhelmsenberg.modular.appearance.AppearanceTheme
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationStore
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import io.github.alexanderwilhelmsenberg.modular.widgets.workspaceHostToken
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Screenshots of the real launcher surfaces, including one bound RemoteViews widget. */
class WorkspaceScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun landingAndAllApps() {
        withRequestedTheme {
            waitForHome()
            rule.onNodeWithTag("workspace-apps-action").assertIsDisplayed()
            rule.onNodeWithTag("alphabet-rail").assertIsDisplayed()
            capture("home")
            rule.onNodeWithTag("workspace-apps-action").performClick()
            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithTag("workspace-all-apps").assertIsDisplayed() }.isSuccess
            }
            capture("all-apps")
        }
    }

    @Test fun actualBoundWidgetSurvivesRecreation() {
        withRequestedTheme {
            waitForHome()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val fixtureContext = instrumentation.context
            val store = OrganizationStore(context)
            val original = runBlocking { store.currentSnapshot().widgets }
            val manager = AppWidgetManager.getInstance(context)
            val host = AppWidgetHost(context, 0x4d4f44)
            val provider = ComponentName(fixtureContext, WorkspaceFixtureProvider::class.java)
            val id = host.allocateAppWidgetId()
            try {
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "appwidget grantbind --package ${context.packageName}",
                )).bufferedReader().use { it.readText() }
                instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
                val bound = try { manager.bindAppWidgetIdIfAllowed(id, provider) }
                    finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
                assertTrue("Fixture provider could not be bound by Android", bound)
                WorkspaceFixtureProvider().onUpdate(fixtureContext, manager, intArrayOf(id))
                runBlocking {
                    val snapshot = store.currentSnapshot()
                    store.update(OrganizationBatch(listOf(OrganizationEdit.SetWidgets(
                        original + WorkspaceWidget(id, "home", provider.flattenToString(), 120,
                            workspaceHostToken(context)),
                    )), snapshot.revision)).getOrThrow()
                }
                rule.waitUntil(15_000) {
                    rule.onAllNodesWithTag("workspace-widget:$id").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("workspace-widget:$id").assertIsDisplayed()
                capture("widget")
                rule.activityRule.scenario.recreate()
                rule.onNodeWithTag("workspace-widget:$id").assertIsDisplayed()
            } finally {
                runBlocking {
                    val snapshot = store.currentSnapshot()
                    store.update(OrganizationBatch(listOf(OrganizationEdit.SetWidgets(original)),
                        snapshot.revision)).getOrThrow()
                }
                host.deleteAppWidgetId(id)
            }
        }
    }

    private fun waitForHome() {
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("workspace-home").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("workspace-home").assertIsDisplayed()
    }

    private fun withRequestedTheme(block: () -> Unit) {
        val argument = InstrumentationRegistry.getArguments().getString("workspace_theme")
        if (argument == null) return block()
        val store = AppearanceSettingsStore(rule.activity)
        val previous = runBlocking { store.currentTheme() }
        try {
            runBlocking { store.setTheme(AppearanceTheme.valueOf(argument)).getOrThrow() }
            rule.activityRule.scenario.recreate()
            block()
        } finally {
            runBlocking { store.setTheme(previous).getOrThrow() }
        }
    }

    private fun capture(scene: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = context.resources.configuration
        val width = config.screenWidthDp
        val theme = InstrumentationRegistry.getArguments().getString("workspace_theme")?.lowercase(Locale.ROOT)
            ?: if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        val font = String.format(Locale.ROOT, "%.1f", config.fontScale)
        val direction = if (config.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"
        val file = File(requireNotNull(context.getExternalFilesDir("workspace-screenshots")),
            "workspace-$scene-${width}dp-$theme-font$font-$direction.png")
        FileOutputStream(file).use { out ->
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            bitmap.recycle()
        }
        val destination = "/sdcard/Pictures/ModularWorkspace/${file.name}"
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }
        shell("mkdir -p /sdcard/Pictures/ModularWorkspace")
        shell("cp ${file.absolutePath} $destination")
        check(shell("stat -c %s $destination").toLongOrNull() == file.length()) {
            "Could not preserve workspace screenshot"
        }
    }
}
