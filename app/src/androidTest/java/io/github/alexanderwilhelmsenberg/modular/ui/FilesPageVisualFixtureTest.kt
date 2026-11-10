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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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

    @Test fun capturePopulatedBrowserReviewTrashAndMap() {
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
        for (view in listOf("browser", "review", "trash", "map")) {
            rule.runOnIdle { page = fixture(view) }
            rule.waitForIdle()
            rule.onNodeWithTag(if (view == "map") "files-storage-map" else "files.page.$view")
                .assertExists()
            if (view == "map") {
                rule.onNodeWithTag("files-map-tile:photos").performClick()
                rule.onNodeWithTag("files-map-detail").assertExists()
            }
            capture(view, requestedTheme.lowercase(Locale.ROOT))
        }
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
            "browser" -> {
                page.put("title", "Downloads").put("summary", "12.8 GB · 10% of 128 GB")
                    .put("breadcrumbs", JSONArray().put(JSONObject().put("name", "Phone storage")
                        .put("action", action("Open", "open"))).put(JSONObject().put("name", "Downloads")
                        .put("action", action("Open", "open"))))
                actions.put(action("Trash", "trash")).put(action("Search", "search"))
                    .put(action("Clean", "clean")).put(action("Storage map", "map"))
                items.put(item("photos", "Photos", "folder", "Downloads/Photos", null, null))
                items.put(item("bookwave", "bookwave-0.10.0.apk", "file", "Downloads/bookwave-0.10.0.apk", 37_000_000, "application/vnd.android.package-archive"))
                items.put(item("notes", "project-notes.md", "file", "Downloads/project-notes.md", 14_208, "text/markdown"))
                items.put(item("manual", "user-manual.pdf", "file", "Downloads/user-manual.pdf", 8_400_000, "application/pdf"))
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
