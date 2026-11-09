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
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import io.github.alexanderwilhelmsenberg.modular.appearance.AppearanceSettingsStore
import io.github.alexanderwilhelmsenberg.modular.appearance.AppearanceTheme
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationStore
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePreferences
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceSection
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import io.github.alexanderwilhelmsenberg.modular.workspace.WIDGET_RAIL_INSET_DP
import io.github.alexanderwilhelmsenberg.modular.widgets.workspaceHostToken
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Screenshots of the real launcher surfaces, including bound RemoteViews widgets. */
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
            val originalSnapshot = runBlocking { store.currentSnapshot() }
            val originalWidgets = originalSnapshot.widgets
            val originalPreferences = originalSnapshot.workspacePreferences
            val manager = AppWidgetManager.getInstance(context)
            val host = AppWidgetHost(context, 0x4d4f44)
            val provider = ComponentName(fixtureContext, WorkspaceFixtureProvider::class.java)
            val ids = mutableListOf<Int>()
            val density = context.resources.displayMetrics.density
            val fixturePreferences = originalPreferences.copy(
                showClock = false, showDate = false, showBattery = false,
                sectionOrder = listOf(WorkspaceSection.WIDGETS, WorkspaceSection.CLOCK,
                    WorkspaceSection.MOST_USED, WorkspaceSection.LISTENING),
                widgetAreaWidthPercent = 100, widgetMarginDp = 16, widgetGapDp = 12,
            )
            fun place(widgets: List<WorkspaceWidget>, preferences: WorkspacePreferences,
                visibleIds: List<Int>) {
                runBlocking {
                    val snapshot = store.currentSnapshot()
                    store.update(OrganizationBatch(listOf(
                        OrganizationEdit.SetWidgets(widgets + originalWidgets),
                        OrganizationEdit.SetWorkspacePreferences(preferences),
                    ), snapshot.revision)).getOrThrow()
                }
                rule.onNodeWithTag("workspace-home").performScrollToIndex(0)
                rule.waitUntil(15_000) {
                    visibleIds.all { id ->
                        rule.onAllNodesWithTag("workspace-widget:$id")
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                }
                rule.waitForIdle()
            }
            fun bounds(id: Int) = rule.onNodeWithTag("workspace-widget:$id")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            fun assertNear(label: String, actual: Float, expected: Float) {
                assertTrue("$label: expected $expected px, got $actual px",
                    abs(actual - expected) <= 3f * density)
            }
            try {
                repeat(3) { ids += host.allocateAppWidgetId() }
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "appwidget grantbind --package ${context.packageName}",
                )).bufferedReader().use { it.readText() }
                ids.forEach { id ->
                    instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
                    val bound = try { manager.bindAppWidgetIdIfAllowed(id, provider) }
                        finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
                    assertTrue("Fixture provider could not be bound by Android", bound)
                }
                WorkspaceFixtureProvider().onUpdate(fixtureContext, manager, ids.toIntArray())
                val token = workspaceHostToken(context)
                val full = WorkspaceWidget(ids[0], "home", provider.flattenToString(), 120,
                    token, span = 2, paddingDp = 0)
                val firstHalf = WorkspaceWidget(ids[1], "home", provider.flattenToString(), 120,
                    token, span = 1, paddingDp = 0)
                val secondHalf = WorkspaceWidget(ids[2], "home", provider.flattenToString(), 120,
                    token, span = 1, paddingDp = 0)

                place(listOf(full, firstHalf, secondHalf), fixturePreferences, listOf(ids[0]))
                val initialFullTop = bounds(ids[0]).top
                capture("widget")
                place(listOf(firstHalf, secondHalf, full), fixturePreferences, ids.drop(1))
                rule.waitUntil(15_000) {
                    runCatching { bounds(ids[1]).top <= initialFullTop + 3f * density }.getOrDefault(false)
                }
                val pair = listOf(bounds(ids[1]), bounds(ids[2])).sortedBy { it.left }
                val home = rule.onNodeWithTag("workspace-home").fetchSemanticsNode().boundsInRoot
                assertNear("paired widget top edges", pair[0].top, pair[1].top)
                assertTrue("Half widgets must be side by side", pair[0].right < pair[1].left)
                assertTrue("Paired widgets must stay outside the alphabet rail",
                    pair[1].right <= home.right - WIDGET_RAIL_INSET_DP * density + 3f * density)
                val initialGap = pair[1].left - pair[0].right
                capture("paired-widgets")

                // The native host must report its actual allocated cell to the provider.
                ids.drop(1).forEach { id ->
                    rule.waitUntil(15_000) {
                        manager.getAppWidgetOptions(id)
                            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) > 0
                    }
                    val widthOption = manager.getAppWidgetOptions(id)
                        .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                    assertNear("Provider width option for widget $id", widthOption.toFloat(),
                        bounds(id).width / density)
                }

                place(listOf(firstHalf, secondHalf, full),
                    fixturePreferences.copy(widgetGapDp = 24), ids.drop(1))
                rule.waitUntil(15_000) {
                    val updated = listOf(bounds(ids[1]), bounds(ids[2])).sortedBy { it.left }
                    updated[1].left - updated[0].right > initialGap + 8f * density
                }
                val widerGap = listOf(bounds(ids[1]), bounds(ids[2])).sortedBy { it.left }
                assertTrue("Increasing the gap must narrow the widget cells",
                    widerGap[0].width < pair[0].width)
                capture("paired-widgets-gap")

                val paddedFirst = firstHalf.copy(paddingDp = 16)
                val paddedSecond = secondHalf.copy(paddingDp = 16)
                place(listOf(paddedFirst, paddedSecond, full),
                    fixturePreferences.copy(widgetAreaWidthPercent = 75), ids.drop(1))
                rule.waitUntil(15_000) {
                    runCatching {
                        val a = bounds(ids[1])
                        val b = bounds(ids[2])
                        if (context.resources.configuration.screenWidthDp <= 414) b.top > a.bottom
                        else a.width < widerGap[0].width * 0.9f
                    }.getOrDefault(false)
                }
                val paddedBounds = listOf(bounds(ids[1]), bounds(ids[2]))
                if (context.resources.configuration.screenWidthDp <= 414) {
                    assertTrue("Narrow cells must fall back to separate full-width rows",
                        paddedBounds[1].top > paddedBounds[0].bottom)
                    capture("widget-half-fallback")
                } else {
                    assertNear("Wide padded widgets still pair", paddedBounds[0].top,
                        paddedBounds[1].top)
                    capture("widget-half-padded")
                }

                place(listOf(full, firstHalf, secondHalf),
                    fixturePreferences.copy(widgetMarginDp = 0, widgetGapDp = 0), listOf(ids[0]))
                rule.waitUntil(15_000) {
                    runCatching {
                        abs(bounds(ids[0]).left - home.left) <= 3f * density
                    }.getOrDefault(false)
                }
                val edgeToRail = bounds(ids[0])
                assertNear("zero-margin widget left edge", edgeToRail.left, home.left)
                assertNear("zero-margin widget right edge", edgeToRail.right,
                    home.right - WIDGET_RAIL_INSET_DP * density)
                capture("widget-zero-margin")

                place(listOf(full, firstHalf, secondHalf),
                    fixturePreferences.copy(widgetAreaWidthPercent = 70,
                        widgetMarginDp = 24, widgetGapDp = 20), listOf(ids[0]))
                rule.waitUntil(15_000) { bounds(ids[0]).width < edgeToRail.width * 0.8f }
                val narrowed = bounds(ids[0])
                assertTrue("Selected width and margin must narrow and center the widget",
                    narrowed.left > edgeToRail.left + 24f * density &&
                        narrowed.right < edgeToRail.right - 24f * density)
                capture("widget-narrow-area")

                val tallerPadded = full.copy(heightDp = 296, rowSpan = 3, paddingDp = 8)
                place(listOf(tallerPadded, firstHalf, secondHalf),
                    fixturePreferences.copy(widgetMarginDp = 0, widgetGapDp = 0), listOf(ids[0]))
                rule.waitUntil(15_000) { bounds(ids[0]).height > edgeToRail.height + 100f * density }
                val tallerBounds = bounds(ids[0])
                assertNear("Widget height resize", tallerBounds.height - edgeToRail.height,
                    108f * density)
                rule.waitUntil(15_000) {
                    manager.getAppWidgetOptions(ids[0])
                        .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) >= 292
                }
                val fullOptions = manager.getAppWidgetOptions(ids[0])
                assertNear("Padded provider width option",
                    fullOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat(),
                    tallerBounds.width / density - 16f)
                assertNear("Padded provider height option",
                    fullOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).toFloat(),
                    296f)
                capture("widget-padded-resized")

                place(listOf(firstHalf, secondHalf, full), fixturePreferences, ids.drop(1))
                rule.activityRule.scenario.recreate()
                waitForHome()
                val restored = runBlocking { store.currentSnapshot() }
                assertTrue("Widget layout preferences must persist after recreation",
                    restored.workspacePreferences == fixturePreferences)
                assertTrue("Half widget placements must persist after recreation",
                    restored.widgets == listOf(firstHalf, secondHalf, full) + originalWidgets)
                val recreatedPair = listOf(bounds(ids[1]), bounds(ids[2])).sortedBy { it.left }
                assertNear("recreated paired widget top edges", recreatedPair[0].top, recreatedPair[1].top)
                assertTrue("Recreated widgets must still share a row",
                    recreatedPair[0].right < recreatedPair[1].left)
            } finally {
                try {
                    runBlocking {
                        val snapshot = store.currentSnapshot()
                        store.update(OrganizationBatch(listOf(
                            OrganizationEdit.SetWidgets(originalWidgets),
                            OrganizationEdit.SetWorkspacePreferences(originalPreferences),
                        ), snapshot.revision)).getOrThrow()
                    }
                } finally {
                    ids.forEach { id -> runCatching { host.deleteAppWidgetId(id) } }
                }
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
