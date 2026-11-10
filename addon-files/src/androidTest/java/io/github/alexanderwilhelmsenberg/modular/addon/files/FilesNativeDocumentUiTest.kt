package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.app.AppOpsManager
import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.StorageLocation
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.createFilesRepository
import io.github.alexanderwilhelmsenberg.modular.filesui.FilesPage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Native page renderer plus real module controller and scoped PFDs, on disposable files. */
class FilesNativeDocumentUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun markdownEditSavesTwiceAndPdfAdvancesFromFirstToSecondPage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.GET_APP_OPS_STATS")
        val originalMode = try { appOps.unsafeCheckOpNoThrow("android:manage_external_storage",
            Process.myUid(), context.packageName) } finally { automation.dropShellPermissionIdentity() }
        val name = "ModularFilesNativeDocs-${UUID.randomUUID()}"
        val fixture = File(Environment.getExternalStorageDirectory(), name)
        try {
            shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!Environment.isExternalStorageManager() && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(100)
            assertTrue(Environment.isExternalStorageManager())
            val repository = createFilesRepository(context)
            val root = repository.locations().single { it.kind == StorageLocation.Kind.PHONE }
            val folder = repository.createDirectory(root.rootId, name)
            val markdown = repository.createFile(folder.id, "note.md", "text/markdown")
            val pdf = repository.createFile(folder.id, "two-pages.pdf", "application/pdf")
            File(fixture, "note.md").writeBytes("# Initial\r\n".toByteArray())
            writePdf(File(fixture, "two-pages.pdf"))
            context.getSharedPreferences("files-page", 0).edit().clear()
                .putString("view", "browser").putString("location", root.id)
                .putString("folder", folder.id).commit()
            val controller = FilesPageController(context, repository)
            var page by mutableStateOf(openItem(controller, markdown.id))
            assertTrue(JSONObject(page).getString("view") == "markdown")
            rule.setContent {
                MaterialTheme {
                    FilesPage(pageJson = page,
                        onAction = { request -> page = controller.performAction(request) },
                        onOpenDocument = { _, _ -> },
                        documentLoader = { id, mode -> withContext(Dispatchers.IO) { controller.openDocument(id, mode) } },
                        documentCommit = { request ->
                            val response = withContext(Dispatchers.IO) { controller.performAction(request) }
                            page = response
                            JSONObject(response).optString("documentSaveStatus") == "saved"
                        },
                        documentCancel = { request ->
                            page = withContext(Dispatchers.IO) { controller.performAction(request) }
                        })
                }
            }
            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithText("Initial").assertExists() }.isSuccess
            }
            rule.onNodeWithText("Edit").performClick()
            rule.onNodeWithTag("files.document.markdown.editor").performTextReplacement("# First\n")
            rule.onNodeWithTag("files.document.markdown.save").performClick()
            val noteFile = File(fixture, "note.md")
            rule.waitUntil(15_000) { noteFile.readText() == "# First\r\n" }
            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithText("Saved").assertExists() }.isSuccess
            }
            assertArrayEquals("# First\r\n".toByteArray(), noteFile.readBytes())
            rule.onNodeWithTag("files.document.markdown.editor").performTextReplacement("# Second\n")
            rule.onNodeWithTag("files.document.markdown.save").performClick()
            rule.waitUntil(15_000) { noteFile.readText() == "# Second\r\n" }
            assertArrayEquals("# Second\r\n".toByteArray(), noteFile.readBytes())

            val back = JSONObject(controller.getPage("""{"version":1,"pageSize":100}"""))
            val backAction = (0 until back.getJSONArray("actions").length())
                .map { back.getJSONArray("actions").getJSONObject(it) }
                .single { it.optString("kind") == "back" }
            controller.performAction(request(backAction.getString("id")))
            val pdfPage = openItem(controller, pdf.id)
            assertTrue(JSONObject(pdfPage).getString("view") == "pdf")
            rule.runOnIdle { page = pdfPage }
            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithTag("files.document.pdf.page")
                    .assertTextContains("Page 1 of 2") }.isSuccess
            }
            rule.onNodeWithTag("files.document.pdf.next").performClick()
            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithTag("files.document.pdf.page")
                    .assertTextContains("Page 2 of 2") }.isSuccess
            }
        } finally {
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
            if (fixture.name == name && fixture.parentFile?.canonicalFile ==
                Environment.getExternalStorageDirectory().canonicalFile) fixture.deleteRecursively()
            shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE ${modeName(originalMode)}")
        }
    }

    private fun openItem(controller: FilesPageController, id: String): String {
        val browser = JSONObject(controller.getPage("""{"version":1,"pageSize":100}"""))
        val items = browser.getJSONArray("items")
        val item = (0 until items.length()).map { items.getJSONObject(it) }.single { it.getString("id") == id }
        val actions = item.getJSONArray("actions")
        val open = (0 until actions.length()).map { actions.getJSONObject(it) }
            .single { it.optString("kind") == "open" }
        return controller.performAction(request(open.getString("id")))
    }

    private fun request(id: String) = JSONObject().put("version", 1).put("actionId", id)
        .put("operationToken", UUID.randomUUID().toString()).toString()

    private fun writePdf(file: File) {
        val document = PdfDocument()
        try {
            repeat(2) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                page.canvas.drawText("Page ${index + 1}", 32f, 80f, Paint().apply { textSize = 18f })
                document.finishPage(page)
            }
            FileOutputStream(file).use(document::writeTo)
        } finally { document.close() }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return try { FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() } }
        finally { descriptor.close() }
    }

    private fun modeName(mode: Int) = when (mode) {
        AppOpsManager.MODE_ALLOWED -> "allow"
        AppOpsManager.MODE_IGNORED -> "ignore"
        AppOpsManager.MODE_ERRORED -> "deny"
        AppOpsManager.MODE_DEFAULT -> "default"
        AppOpsManager.MODE_FOREGROUND -> "foreground"
        else -> error("Unknown original All files access mode: $mode")
    }
}
