package io.github.alexanderwilhelmsenberg.modular.ui

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
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
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Tests Android's real widget binding, measured grid cells, and persisted placement. */
class WidgetGridIntegrationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun advertisedFourByFourOccupiesFourRowsAndReportsTheAllocatedSize() = withFixture { fixture ->
        val widget = fixture.add(GridFixtureLargeProvider::class.java)
        assertEquals(4, widget.columnSpan)
        assertEquals(4, widget.rowSpan)

        fixture.placeAtStart(listOf(widget))
        val cell = fixture.cell(widget.appWidgetId)
        val expectedHeightDp = 4 * 96 + 3 * 8
        fixture.assertNear("4 by 4 cell height", cell.height, expectedHeightDp.toFloat())
        val widthOption = fixture.optionNear(widget.appWidgetId,
            AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, cell.width / fixture.density)
        val heightOption = fixture.optionNear(widget.appWidgetId,
            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, expectedHeightDp.toFloat())
        fixture.assertDpNear("provider width option", widthOption.toFloat(), cell.width / fixture.density)
        fixture.assertDpNear("provider height option", heightOption.toFloat(), expectedHeightDp.toFloat())
        capture("four-by-four")

        rule.activityRule.scenario.recreate()
        fixture.waitForHome()
        val restored = fixture.snapshot().widgets.single { it.appWidgetId == widget.appWidgetId }
        assertEquals(4, restored.columnSpan)
        assertEquals(4, restored.rowSpan)
        fixture.assertNear("recreated 4 by 4 cell height", fixture.cell(widget.appWidgetId).height,
            expectedHeightDp.toFloat())
    }

    @Test fun fourSmallWidgetsShareOneRowAndRetainTheirCells() = withFixture { fixture ->
        val widgets = (1..4).map { fixture.add(GridFixtureSmallProvider::class.java) }
        widgets.forEach { widget ->
            assertEquals(1, widget.columnSpan)
            assertEquals(1, widget.rowSpan)
        }
        fixture.placeAtStart(widgets)
        val firstBounds = widgets.map { fixture.cell(it.appWidgetId) }
        assertFourAcross(fixture, firstBounds)
        capture("four-small")

        rule.activityRule.scenario.recreate()
        fixture.waitForHome()
        assertEquals(widgets.map { it.appWidgetId }, fixture.snapshot().widgets.take(4).map { it.appWidgetId })
        val restoredBounds = widgets.map { fixture.cell(it.appWidgetId) }
        assertFourAcross(fixture, restoredBounds)
        rule.onNodeWithTag("workspace-home-action").assertIsNotDisplayed()

        // The grid is part of Home; the Apps drawer covers it and reveals Home again on Back.
        rule.onNodeWithTag("workspace-apps-action").performClick()
        rule.waitUntil(15_000) {
            runCatching { rule.onNodeWithTag("workspace-all-apps").assertIsDisplayed() }.isSuccess
        }
        rule.waitUntil(15_000) {
            val cells = rule.onAllNodesWithTag("workspace-widget-cell:${widgets.first().appWidgetId}")
            cells.fetchSemanticsNodes().isEmpty() ||
                runCatching { cells[0].assertIsNotDisplayed() }.isSuccess
        }
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        fixture.waitForHome()
        rule.onNodeWithTag("workspace-home-action").assertIsNotDisplayed()
        fixture.cell(widgets.first().appWidgetId)
    }

    @Test fun widgetLongPressAndEmptySpaceExposeGridEditingActions() = withFixture { fixture ->
        val widget = fixture.add(GridFixtureSmallProvider::class.java)
        fixture.placeAtStart(listOf(widget))
        nativeLongPress(widget.appWidgetId)
        rule.onNodeWithTag("workspace-widget-controls:${widget.appWidgetId}").assertIsDisplayed()
        rule.onNodeWithTag("workspace-widget-move:${widget.appWidgetId}").assertIsDisplayed()
        rule.onNodeWithTag("workspace-widget-resize:${widget.appWidgetId}").assertIsDisplayed()
        capture("edit")

        val originalCell = fixture.cell(widget.appWidgetId)
        val columnStep = originalCell.width + 8f * fixture.density
        val rowStep = 104f * fixture.density
        drag("workspace-widget-move:${widget.appWidgetId}", columnStep, rowStep)
        rule.waitUntil(15_000) {
            fixture.snapshot().widgets.any {
                it.appWidgetId == widget.appWidgetId && it.gridColumn == 1 && it.gridRow == 1
            }
        }
        val movedCell = fixture.cell(widget.appWidgetId)
        fixture.assertNear("moved one column", movedCell.left - originalCell.left,
            columnStep / fixture.density)
        fixture.assertNear("moved one row", movedCell.top - originalCell.top, 104f)

        drag("workspace-widget-resize:${widget.appWidgetId}", columnStep, rowStep)
        rule.waitUntil(15_000) {
            fixture.snapshot().widgets.any {
                it.appWidgetId == widget.appWidgetId && it.columnSpan == 2 && it.rowSpan == 2 &&
                    it.gridColumn == 1 && it.gridRow == 1
            }
        }
        val resizedCell = fixture.cell(widget.appWidgetId)
        fixture.assertNear("two-row resized cell", resizedCell.height, 2 * 96f + 8f)
        fixture.assertNear("two-column resized cell", resizedCell.width,
            (2 * originalCell.width + 8f * fixture.density) / fixture.density)
        val grid = gridContaining(resizedCell)
        assertTrue("Resized widget must remain inside its grid", resizedCell.right <= grid.right)
        fixture.snapshot().widgets.filter { it.pageId == "home" && it.appWidgetId != widget.appWidgetId }
            .forEach { other ->
                val otherCell = rule.onAllNodesWithTag("workspace-widget-cell:${other.appWidgetId}")
                    .fetchSemanticsNodes().firstOrNull()?.boundsInRoot
                if (otherCell != null) assertTrue("Resized widget overlaps ${other.appWidgetId}",
                    resizedCell.right <= otherCell.left || resizedCell.left >= otherCell.right ||
                        resizedCell.bottom <= otherCell.top || resizedCell.top >= otherCell.bottom)
            }
        capture("moved-resized")
        rule.onNodeWithTag("workspace-widget-edit-done").performClick()

        rule.onNodeWithTag("workspace-home").performScrollToIndex(0)
        rule.onNodeWithTag("workspace-empty-edit-space").performTouchInput { longClick() }
        rule.onNodeWithTag("workspace-home-menu").assertIsDisplayed()
        rule.onNodeWithTag("workspace-menu-add-widget").assertIsDisplayed()
        rule.onNodeWithTag("workspace-menu-edit").assertIsDisplayed()
        rule.onNodeWithTag("workspace-menu-advanced").assertIsDisplayed()
        capture("menu")
        rule.onNodeWithTag("workspace-menu-advanced").performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("home-grid-settings-content").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("home-grid-settings-content").assertIsDisplayed()
        rule.onNodeWithTag("workspace-home-menu").assertIsNotDisplayed()
        capture("settings")
    }

    private fun nativeLongPress(id: Int) {
        // EditableWidgetHostView uses Android View.postDelayed. Compose's virtual
        // longClick clock can advance past the hold without running that callback.
        val center = rule.onNodeWithTag("workspace-widget:$id")
            .fetchSemanticsNode().boundsInRoot.center
        val origin = IntArray(2)
        rule.runOnIdle {
            rule.activity.findViewById<View>(android.R.id.content).getLocationOnScreen(origin)
        }
        val x = center.x + origin[0]
        val y = center.y + origin[1]
        val downAt = SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                assertTrue("Could not inject native widget touch", InstrumentationRegistry
                    .getInstrumentation().uiAutomation.injectInputEvent(event, true))
            }
            finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN)
        Thread.sleep(ViewConfiguration.getLongPressTimeout() + 200L)
        inject(MotionEvent.ACTION_UP)
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("workspace-widget-controls:$id").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun drag(tag: String, deltaX: Float, deltaY: Float) {
        rule.onNodeWithTag(tag).performTouchInput {
            val start = center
            down(start)
            for (step in 1..10) {
                moveTo(start + Offset(deltaX * step / 10f, deltaY * step / 10f), 20L)
            }
            up()
        }
    }

    private fun assertFourAcross(fixture: Fixture, cells: List<Rect>) {
        assertEquals(4, cells.size)
        cells.forEach { fixture.assertNear("1 by 1 cell height", it.height, 96f) }
        for (index in 1..3) {
            fixture.assertNear("row top $index", cells[index].top, cells[0].top / fixture.density)
            assertTrue("Grid columns must not overlap", cells[index - 1].right < cells[index].left)
        }
        val home = rule.onNodeWithTag("workspace-home").fetchSemanticsNode().boundsInRoot
        assertTrue("Fourth cell must remain inside Home", cells.last().right <= home.right + fixture.density)
    }

    private fun gridContaining(cell: Rect): Rect {
        val bands = rule.onAllNodesWithTag("workspace-widget-grid")
            .fetchSemanticsNodes().map { it.boundsInRoot }
        return bands.firstOrNull { band ->
            cell.left >= band.left - 1f && cell.right <= band.right + 1f &&
                cell.top >= band.top - 1f && cell.bottom <= band.bottom + 1f
        } ?: error("No grid band contains widget cell $cell; bands=$bands")
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val requestedTheme = InstrumentationRegistry.getArguments().getString("workspace_theme")
        val themeStore = if (requestedTheme != null) AppearanceSettingsStore(rule.activity) else null
        val previousTheme = themeStore?.let { runBlocking { it.currentTheme() } }
        try {
            if (requestedTheme != null && themeStore != null) {
                runBlocking { themeStore.setTheme(AppearanceTheme.valueOf(requestedTheme)).getOrThrow() }
                rule.activityRule.scenario.recreate()
            }
            val fixture = Fixture()
            try {
                fixture.prepare()
                block(fixture)
            } finally {
                fixture.restore()
            }
        } finally {
            if (previousTheme != null) {
                runBlocking { themeStore!!.setTheme(previousTheme).getOrThrow() }
            }
        }
    }

    private fun capture(scene: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = context.resources.configuration
        val width = config.screenWidthDp
        val theme = InstrumentationRegistry.getArguments().getString("workspace_theme")?.lowercase(Locale.ROOT)
            ?: if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
                "dark" else "light"
        val font = String.format(Locale.ROOT, "%.1f", config.fontScale)
        val direction = if (config.layoutDirection == View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"
        val file = File(requireNotNull(context.getExternalFilesDir("workspace-screenshots")),
            "grid-$scene-${width}dp-$theme-font$font-$direction.png")
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
            "Could not preserve grid screenshot"
        }
    }

    private inner class Fixture {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val target = instrumentation.targetContext
        private val test = instrumentation.context
        private val store = OrganizationStore(target)
        private val host = AppWidgetHost(target, 0x4d4f44)
        private val original = snapshot()
        private val originalIds = host.appWidgetIds.toSet()
        val density: Float = target.resources.displayMetrics.density
        private val preferences: WorkspacePreferences = original.workspacePreferences.copy(
            showClock = false, showDate = false, showBattery = false,
            sectionOrder = listOf(WorkspaceSection.WIDGETS, WorkspaceSection.CLOCK,
                WorkspaceSection.MOST_USED, WorkspaceSection.LISTENING),
            widgetAreaWidthPercent = 100, widgetMarginDp = 0, widgetGapDp = 8,
            gridColumnCount = 4, gridRowHeightDp = 96,
        )

        fun snapshot() = runBlocking { store.currentSnapshot() }

        fun prepare() {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "appwidget grantbind --package ${target.packageName}",
            )).bufferedReader().use { it.readText() }
            update(original.widgets, preferences)
            waitForHome()
        }

        fun restore() {
            try {
                update(original.widgets, original.workspacePreferences)
            } finally {
                (host.appWidgetIds.toSet() - originalIds).forEach { id ->
                    runCatching { host.deleteAppWidgetId(id) }
                }
            }
        }

        fun waitForHome() {
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("workspace-home").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("workspace-home").assertIsDisplayed()
        }

        fun add(providerClass: Class<*>): WorkspaceWidget {
            val provider = ComponentName(test, providerClass).flattenToString()
            val before = snapshot().widgets.map { it.appWidgetId }.toSet()
            val tag = "widget-provider:$provider"
            rule.onNodeWithTag("workspace-home").performScrollToNode(hasTestTag("workspace-add-widget"))
            rule.onNodeWithTag("workspace-add-widget").performClick()
            rule.onNodeWithTag("widget-provider-list").performScrollToNode(hasTestTag(tag))
            rule.onNodeWithTag(tag).performClick()
            rule.waitUntil(15_000) {
                snapshot().widgets.any { it.provider == provider && it.appWidgetId !in before }
            }
            return snapshot().widgets.single { it.provider == provider && it.appWidgetId !in before }
        }

        fun placeAtStart(widgets: List<WorkspaceWidget>) {
            // Keep every pre-existing ID referenced throughout the test. Give the
            // fixture the first available row even on a device with saved widgets.
            val parked = original.widgets.mapIndexed { index, widget ->
                widget.copy(gridColumn = 0, gridRow = 4 + index * 8)
            }
            update(widgets + parked, preferences)
            rule.onNodeWithTag("workspace-home").performScrollToIndex(0)
            rule.waitUntil(15_000) {
                runCatching {
                    val cells = widgets.map {
                        rule.onNodeWithTag("workspace-widget-cell:${it.appWidgetId}")
                            .fetchSemanticsNode().boundsInRoot
                    }
                    val grid = gridContaining(cells.first())
                    abs(cells.first().left - grid.left) <= density &&
                        cells.drop(1).all { abs(it.top - cells.first().top) <= density }
                }.getOrDefault(false)
            }
        }

        fun cell(id: Int): Rect = rule.onNodeWithTag("workspace-widget-cell:$id")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        fun optionNear(id: Int, key: String, expectedDp: Float): Int {
            val manager = AppWidgetManager.getInstance(target)
            rule.waitUntil(15_000) {
                abs(manager.getAppWidgetOptions(id).getInt(key, 0) - expectedDp) <= 3f
            }
            return manager.getAppWidgetOptions(id).getInt(key)
        }

        fun assertNear(label: String, actual: Float, expectedDp: Float) {
            assertTrue("$label: expected $expectedDp dp, got ${actual / density} dp",
                abs(actual / density - expectedDp) <= 3f)
        }

        fun assertDpNear(label: String, actualDp: Float, expectedDp: Float) {
            assertTrue("$label: expected $expectedDp dp, got $actualDp dp",
                abs(actualDp - expectedDp) <= 3f)
        }

        private fun update(widgets: List<WorkspaceWidget>, prefs: WorkspacePreferences) {
            runBlocking {
                val current = store.currentSnapshot()
                store.update(OrganizationBatch(listOf(
                    OrganizationEdit.SetWidgets(widgets),
                    OrganizationEdit.SetWorkspacePreferences(prefs),
                ), current.revision)).getOrThrow()
            }
        }
    }
}
