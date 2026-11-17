package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.graphics.Bitmap
import android.view.View
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.alexanderwilhelmsenberg.modular.filesui.FilesPage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/** Physical screen-coordinate taps, with the Files viewport below a simulated host toolbar. */
class FilesMenuTouchTest {
    @get:Rule val rule = createComposeRule()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var owner: View
    private val requests = mutableListOf<JSONObject>()

    @Test fun visibleDropdownRowsReceivePhysicalTapsInsideAnInsetViewport() {
        show()
        val windows = rule.runOnIdle { WindowInspector.getGlobalWindowViews().size }
        for (kind in listOf("clean", "info", "map", "settings")) {
            tap("files.action.more")
            rule.onNodeWithTag("files.menu").assertIsDisplayed()
            assertEquals("Menus must not create a separate input window", windows,
                rule.runOnIdle { WindowInspector.getGlobalWindowViews().size })
            if (kind == "clean") capture("toolbar")
            rule.onNodeWithTag("files.action.$kind").performScrollTo()
            tap("files.action.$kind")
            rule.onNodeWithTag("files.menu").assertDoesNotExist()
            rule.runOnIdle { assertEquals(kind, requests.last().getString("actionId")) }
        }
        tap("files.action.more")
        device.pressBack()
        rule.waitForIdle()
        rule.onNodeWithTag("files.menu").assertDoesNotExist()
        assertEquals(4, requests.size)
    }

    @Test fun longPressOffersDeleteAndOutsideTapDoesNotOpenTheFolder() {
        show()
        rule.onNodeWithTag("files.folder.row:photos").performTouchInput { longClick() }
        for (kind in listOf("copy", "cut", "move", "review")) {
            rule.onNodeWithTag("files.item.action.$kind").performScrollTo().assertIsDisplayed()
        }
        capture("folder")
        tap("files.item.action.review")
        rule.runOnIdle { assertEquals("delete-photos", requests.single().getString("actionId")) }
        val bounds = rule.onNodeWithTag("files.page.browser").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("files.folder.row:photos").performTouchInput { longClick() }
        // Outside the menu, inside the viewport: dismiss without clicking through.
        val origin = IntArray(2)
        rule.runOnIdle { owner.getLocationOnScreen(origin) }
        device.click(origin[0] + bounds.left.roundToInt() + 2, origin[1] + bounds.bottom.roundToInt() - 2)
        rule.waitForIdle()
        rule.onNodeWithTag("files.menu").assertDoesNotExist()
        assertEquals(1, requests.size)
    }

    private fun show() {
        val theme = InstrumentationRegistry.getArguments().getString("files_theme") ?: "LIGHT"
        rule.setContent {
            owner = LocalView.current
            val colors = when (theme) {
                "DARK" -> darkColorScheme()
                "AMOLED" -> darkColorScheme(background = Color.Black, surface = Color.Black)
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                Box(Modifier.fillMaxSize().padding(top = 92.dp, bottom = 24.dp, start = 8.dp, end = 8.dp)) {
                    FilesPage(fixture(), onAction = { requests.add(JSONObject(it)) }, onOpenDocument = { _, _ -> })
                }
            }
        }
        rule.waitForIdle()
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).assertIsDisplayed()
        val center = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center
        val origin = IntArray(2)
        rule.runOnIdle { owner.getLocationOnScreen(origin) }
        assertTrue(device.click(origin[0] + center.x.roundToInt(), origin[1] + center.y.roundToInt()))
        rule.waitForIdle()
    }

    private fun capture(scene: String) {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("files_capture") != "true") return
        val width = args.getString("files_width") ?: "default"
        val theme = (args.getString("files_theme") ?: "LIGHT").lowercase()
        val output = File("/sdcard/Pictures/ModularFiles/files-menu-$scene-${width}dp-$theme.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun fixture(): String {
        fun action(id: String, label: String, kind: String) = JSONObject().put("id", id).put("label", label).put("kind", kind)
        val actions = JSONArray().put(action("clean", "Clean", "clean")).put(action("info", "Info", "info"))
            .put(action("map", "Storage map", "map")).put(action("settings", "Settings", "settings"))
        val row = JSONArray().put(action("open", "Open", "open")).put(action("copy", "Copy", "copy"))
            .put(action("cut", "Cut", "cut")).put(action("move", "Move…", "move"))
            .put(action("delete-photos", "Delete", "review"))
        return JSONObject().put("version", 1).put("kind", "files").put("view", "browser")
            .put("title", "Downloads").put("locationId", "fixture").put("folderId", "downloads")
            .put("actions", actions).put("items", JSONArray().put(JSONObject().put("id", "photos")
                .put("name", "Photos").put("kind", "folder").put("actions", row))).toString()
    }
}
