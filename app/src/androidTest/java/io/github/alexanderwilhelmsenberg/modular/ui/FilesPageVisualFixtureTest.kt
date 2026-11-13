package io.github.alexanderwilhelmsenberg.modular.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.filesui.FilesPage
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Populated native renderer fixtures for design review; file operations are tested separately. */
class FilesPageVisualFixtureTest {
    @get:Rule val rule = createComposeRule()

    @Test fun capturePopulatedBrowserReviewTrashMapAndSettings() {
        val requestedTheme = InstrumentationRegistry.getArguments().getString("files_theme") ?: "LIGHT"
        val colors = when (requestedTheme) {
            "DARK" -> darkColorScheme()
            "AMOLED" -> darkColorScheme(background = Color.Black, surface = Color.Black,
                surfaceContainer = Color(0xFF101010))
            else -> lightColorScheme()
        }
        var page by mutableStateOf(fixture("browser"))
        rule.setContent {
            MaterialTheme(colorScheme = colors) {
                FilesPage(pageJson = page, onAction = {}, onOpenDocument = { _, _ -> })
            }
        }
        for (scene in listOf("browser", "browser-details", "review", "trash", "map", "settings")) {
            val view = if (scene == "browser-details") "browser" else scene
            rule.runOnIdle { page = fixture(scene) }
            rule.waitForIdle()
            rule.onNodeWithTag(if (view == "map") "files-storage-map" else "files.page.$view")
                .assertExists()
            if (view == "map") {
                rule.onNodeWithTag("files-map-tile:photos").performClick()
                rule.onNodeWithTag("files-map-detail").assertExists()
            }
            if (view == "settings") {
                rule.onNodeWithText("Add folder").assertIsDisplayed()
                rule.onNodeWithText("Large file threshold (MiB)").assertIsDisplayed()
                rule.onNodeWithText("Android still protects", substring = true).assertDoesNotExist()
            }
            if (view == "browser") {
                rule.onNodeWithTag("files.search.input").assertIsDisplayed()
                rule.onNodeWithTag("files.create.menu").assertIsDisplayed()
                rule.onNodeWithTag("files.folder.row:photos").assertIsDisplayed()
            }
            capture(scene, requestedTheme.lowercase(Locale.ROOT))
            if (view == "settings") {
                rule.onNodeWithText("Theme: system").performScrollTo().assertIsDisplayed()
                rule.onNodeWithTag("files.action.more").performClick()
                rule.onNodeWithTag("files.action.info").assertIsDisplayed()
            }
        }
    }

    @Test fun captureMenuFolderAppearanceAndCleanSheet() {
        val requestedTheme = InstrumentationRegistry.getArguments().getString("files_theme") ?: "LIGHT"
        val colors = when (requestedTheme) {
            "DARK" -> darkColorScheme()
            "AMOLED" -> darkColorScheme(background = Color.Black, surface = Color.Black,
                surfaceContainer = Color(0xFF101010))
            else -> lightColorScheme()
        }
        var page by mutableStateOf(fixture("browser"))
        val requests = mutableListOf<JSONObject>()
        rule.setContent {
            MaterialTheme(colorScheme = colors) {
                FilesPage(pageJson = page, onAction = {
                    val request = JSONObject(it)
                    requests.add(request)
                    val args = request.optJSONObject("args")
                    if (args != null && (args.has("icon") || args.has("color"))) {
                        val updated = JSONObject(page)
                        val appearance = updated.getJSONObject("folderAppearance")
                        if (args.has("icon")) appearance.put("icon", args.getString("icon"))
                        if (args.has("color")) appearance.put("color", args.getString("color"))
                        page = updated.toString()
                    }
                },
                    onOpenDocument = { _, _ -> })
            }
        }
        rule.onNodeWithTag("files.folder.row:photos").assertIsDisplayed()
        rule.onNodeWithTag("files.action.more").performClick()
        rule.onNodeWithTag("files.action.info").assertIsDisplayed()
        rule.onNodeWithTag("files.action.clean").assertIsDisplayed()
        rule.onNodeWithTag("files.action.map").assertIsDisplayed()
        capture("browser-menu", requestedTheme.lowercase(Locale.ROOT))
        rule.onNodeWithTag("files.action.info").performClick()
        rule.onNodeWithTag("files.create.menu").performClick()
        rule.onNodeWithTag("files.create.file").performClick()
        rule.onNodeWithTag("files.create.name").performTextInput("notes")
        rule.onNodeWithText("Create").performClick()
        rule.runOnIdle {
            assertEquals("notes", requests.last().getJSONObject("args").getString("name"))
            assertEquals("txt", requests.last().getJSONObject("args").getString("extension"))
        }
        rule.onNodeWithTag("files.folder.row:photos").performTouchInput { longClick() }
        rule.onNodeWithTag("files.item.action.folderAppearance").assertIsDisplayed()
        capture("folder-menu", requestedTheme.lowercase(Locale.ROOT))

        rule.runOnIdle { page = fixture("folderAppearance") }
        rule.onNodeWithTag("files.folder.appearance").assertIsDisplayed()
        rule.onNodeWithTag("files.folder.icon:game").performClick()
        rule.runOnIdle { assertEquals("game", requests.last().getJSONObject("args").getString("icon")) }
        rule.onNodeWithTag("files.folder.appearance.options")
            .performScrollToNode(hasTestTag("files.folder.color:#6795D4"))
        rule.onNodeWithTag("files.folder.color:#6795D4").performClick()
        rule.runOnIdle { assertEquals("#6795D4", requests.last().getJSONObject("args").getString("color")) }
        capture("folder-appearance", requestedTheme.lowercase(Locale.ROOT))

        rule.runOnIdle { page = fixture("progress") }
        rule.onNodeWithTag("files.clean.sheet").assertIsDisplayed()
        rule.onNodeWithTag("files.clean.progress").assertIsDisplayed()
        rule.onNodeWithTag("files.clean.findings").assertExists()
        rule.onNodeWithText("Project notes").assertIsDisplayed()
        rule.onNodeWithTag("files.clean.close").assertIsDisplayed()
        rule.onNodeWithTag("files.clean.cancel").assertIsDisplayed()
        rule.onNodeWithText("Choose keeper").assertDoesNotExist()
        capture("clean-progress", requestedTheme.lowercase(Locale.ROOT))
        rule.runOnIdle { page = fixture("clean") }
        rule.onNodeWithTag("files.clean.sheet").assertIsDisplayed()
        rule.onNodeWithText("Clean folder").assertIsDisplayed()
        rule.onNodeWithText("Choose keeper").assertIsDisplayed()
        rule.onNodeWithText("Include").assertIsDisplayed()
        capture("clean-sheet", requestedTheme.lowercase(Locale.ROOT))

        rule.runOnIdle { page = fixture("info") }
        rule.onNodeWithTag("files.page.info").assertIsDisplayed()
        rule.onNodeWithText("Access").assertIsDisplayed()
        rule.onNodeWithText("Android still protects", substring = true).assertIsDisplayed()
    }

    private fun fixture(view: String): String {
        val page = JSONObject().put("version", 1).put("kind", "files").put("view", view)
            .put("state", "ready").put("locationId", "fixture-volume")
            .put("folderId", "fixture-downloads")
            .put("trash", JSONObject().put("fileCount", if (view == "trash") 21 else 1)
                .put("emptyFolderCount", 0).put("knownBytes", 48_000_000))
        val actions = JSONArray()
        fun action(label: String, kind: String) = JSONObject().put("id", "fixture-$kind-$label")
            .put("label", label).put("kind", kind)
        val items = JSONArray()
        when (view) {
            "browser", "browser-details" -> {
                page.put("view", "browser").put("layout", if (view == "browser-details") "details" else "list")
                page.put("title", "Downloads").put("summary", "12.8 GB · 10% of 128 GB")
                    .put("currentFolder", appearance("fixture-downloads", "Downloads"))
                    .put("breadcrumbs", JSONArray().put(JSONObject().put("name", "Phone storage")
                        .put("action", action("Open", "open"))).put(JSONObject().put("name", "Downloads")
                        .put("action", action("Open", "open"))))
                actions.put(action("Trash", "trash")).put(action("Search", "search"))
                    .put(action("Clean", "clean")).put(action("Info", "info"))
                    .put(action("Storage map", "map"))
                    .put(action("New folder", "create").put("input", "name"))
                    .put(action("New file", "createFile").put("input", "textFile"))
                    .put(action("Customize folder", "folderAppearance"))
                    .put(action("Sort name", "sort")).put(action("Details view", "layout"))
                    .put(action("Settings", "settings"))
                items.put(item("photos", "Photos", "folder", "Downloads/Photos", null, null)
                    .put("folderAppearance", appearance("photos", "Photos"))
                    .put("actions", JSONArray().put(action("Open", "open"))
                        .put(action("Customize folder", "folderAppearance"))
                        .put(action("Cut", "cut")).put(action("Copy", "copy"))
                        .put(action("Move", "move")).put(action("Delete", "delete"))))
                items.put(item("bookwave", "bookwave-0.10.0.apk", "file", "Downloads/bookwave-0.10.0.apk", 37_000_000, "application/vnd.android.package-archive"))
                items.put(item("notes", "project-notes.md", "file", "Downloads/project-notes.md", 14_208, "text/markdown"))
                items.put(item("manual", "user-manual.pdf", "file", "Downloads/user-manual.pdf", 8_400_000, "application/pdf"))
            }
            "folderAppearance" -> {
                page.put("title", "Photos").put("summary", "Choose an icon and color for this folder")
                    .put("folderAppearance", appearance("photos", "Photos"))
                    .put("folderAppearanceOptions", JSONObject()
                        .put("icons", options("folder" to "Folder", "game" to "Games",
                            "audio" to "Audio", "music" to "Music", "pictures" to "Pictures",
                            "video" to "Video", "downloads" to "Downloads",
                            "documents" to "Documents", "archive" to "Archives",
                            "camera" to "Camera", "work" to "Work", "home" to "Home",
                            "books" to "Books", "code" to "Code", "apps" to "Apps",
                            "star" to "Favorites"))
                        .put("colors", options("auto" to "Automatic", "#E7B93F" to "Yellow",
                            "#E77D68" to "Coral", "#D98DBA" to "Pink",
                            "#A58BD3" to "Purple", "#6795D4" to "Blue",
                            "#63BDB3" to "Teal", "#78B778" to "Green",
                            "#A8A8A8" to "Grey")))
                actions.put(action("Back to folder", "back"))
                    .put(action("Choose icon", "setFolderIcon"))
                    .put(action("Choose color", "setFolderColor"))
                    .put(action("Reset appearance", "resetFolderAppearance"))
            }
            "progress" -> {
                page.put("title", "Working").put("summary", "Scanning cleanup candidates")
                    .put("presentation", "sheet").put("underlyingView", "browser")
                    .put("progressFor", "clean").put("cleanScanRunning", true).put("complete", false)
                actions.put(action("Close", "back")).put(action("Cancel", "cancel"))
                    .put(action("Refresh", "refresh"))
                items.put(item("copies", "Project notes", "group", "Downloads/Project notes", 2048, null)
                    .put("subtitle", "numbered copies · 2 items")
                    .put("actions", JSONArray().put(action("Choose keeper", "keeper"))))
            }
            "clean" -> {
                page.put("title", "Recommended cleanup")
                    .put("summary", "1 duplicate/copy/version groups · 1 other suggestions")
                    .put("presentation", "sheet").put("underlyingView", "browser")
                actions.put(action("Back to folder", "back"))
                    .put(action("Scan again", "scan"))
                    .put(action("Review selected", "review"))
                items.put(item("copies", "Project notes", "group", "Downloads/Project notes", 2048, null)
                    .put("subtitle", "numbered copies · 2 items")
                    .put("actions", JSONArray().put(action("Included", "select"))
                        .put(action("Choose keeper", "keeper"))))
                items.put(item("old", "old-installer.apk", "suggestion", "Downloads/old-installer.apk", 37_000_000, null)
                    .put("subtitle", "old download · Older installer")
                    .put("actions", JSONArray().put(action("Include", "select"))))
            }
            "info" -> {
                page.put("title", "Downloads").put("summary", "Storage details for this folder")
                    .put("currentFolder", appearance("fixture-downloads", "Downloads"))
                actions.put(action("Back to folder", "back"))
                items.put(item("info:Path", "Path", "detail", "Downloads", null, null))
                items.put(item("info:Access", "Access", "detail", "Downloads", null, null)
                    .put("subtitle", "Android still protects other apps’ private data."))
            }
            "settings" -> {
                page.put("title", "Files settings")
                    .put("summary", "Manage storage access, cleanup thresholds, and appearance.")
                actions.put(action("Back", "back")).put(action("Info", "info"))
                    .put(action("Add folder", "setup"))
                    .put(action("Enable full storage access", "setup"))
                    .put(action("Large file threshold (MiB)", "setting"))
                    .put(action("Old download days", "setting"))
                    .put(action("Clear 0 exclusions", "setting"))
                    .put(action("Theme: system", "setting"))
            }
            "review" -> {
                page.put("title", "Review deletion").put("summary", "3 files · 19.5 MB · Move to trash")
                    .put("complete", true)
                actions.put(action("Cancel", "cancel")).put(action("Delete", "confirm"))
                items.put(item("old-apk", "bookwave-0.8.1.apk", "file", "Downloads/old/bookwave-0.8.1.apk", 12_400_000, "application/vnd.android.package-archive"))
                items.put(item("numbered", "manual(12).pdf", "file", "Downloads/manual(12).pdf", 7_000_000, "application/pdf"))
                items.put(item("nested", "readme.txt", "file", "Downloads/copy(1)/readme.txt", 100_000, "text/plain"))
            }
            "trash" -> {
                page.put("title", "Trash").put("summary", "21 files · 45.8 MB in trash")
                actions.put(action("Back", "back")).put(action("Restore all", "restore"))
                    .put(action("Empty trash", "review"))
                items.put(item("trash-1", "bookwave-0.8.1.apk", "trash", "Downloads/old/bookwave-0.8.1.apk", 12_400_000, null))
                items.put(item("trash-2", "manual(12).pdf", "trash", "Downloads/manual(12).pdf", 7_000_000, null))
                items.put(item("trash-3", "old-photo.jpg", "trash", "Pictures/old-photo.jpg", 2_000_000, "image/jpeg"))
            }
            "map" -> {
                page.put("title", "Storage map").put("mapPath", "Phone storage/Downloads")
                    .put("mapTotalBytes", 128_000_000).put("mapComplete", false)
                actions.put(action("Back to files", "back")).put(action("Trash", "trash"))
                page.put("mapNodes", JSONArray()
                    .put(item("photos", "Photos", "folder", "Downloads/Photos", 78_000_000, null)
                        .put("actions", JSONArray().put(action("Zoom", "open")).put(action("Open", "open"))))
                    .put(item("videos", "holiday.mp4", "file", "Downloads/holiday.mp4", 30_000_000, "video/mp4"))
                    .put(item("archive", "backup.zip", "file", "Downloads/backup.zip", 18_000_000, "application/zip"))
                    .put(item("unknown", "Cloud export", "folder", "Downloads/Cloud export", null, null)
                        .put("partial", true)))
            }
        }
        return page.put("items", items).put("actions", actions).put("nextCursor", JSONObject.NULL).toString()
    }

    private fun item(id: String, name: String, kind: String, path: String, bytes: Long?, mime: String?): JSONObject =
        JSONObject().put("id", id).put("name", name).put("kind", kind).put("path", path)
            .put("sizeBytes", bytes).put("mimeType", mime).put("modifiedAt", 1_699_900_000_000L)
            .put("createdAt", JSONObject.NULL)

    private fun appearance(id: String, name: String): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("icon", "folder").put("color", "auto")
        .put("customIcon", false).put("customColor", false)

    private fun option(id: String, label: String): JSONObject = JSONObject()
        .put("id", id).put("label", label)

    private fun options(vararg values: Pair<String, String>): JSONArray = JSONArray().also { array ->
        values.forEach { (id, label) -> array.put(option(id, label)) }
    }

    private fun capture(scene: String, theme: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = context.resources.configuration
        val font = String.format(Locale.ROOT, "%.1f", config.fontScale)
        val name = "files-fixture-$scene-${config.screenWidthDp}dp-$theme-font$font.png"
        val file = File(requireNotNull(context.getExternalFilesDir("files-screenshots")), name)
        FileOutputStream(file).use { output ->
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
        }
        val directory = "/sdcard/Pictures/ModularFiles"
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText().trim() }
        shell("mkdir -p $directory")
        shell("cp ${file.absolutePath} $directory/$name")
        assertEquals(file.length(), shell("stat -c %s $directory/$name").toLong())
    }
}
