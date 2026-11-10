package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.app.AppOpsManager
import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.addon.files.documents.PdfDocumentSession
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.StorageLocation
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.createFilesRepository
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opens a generated PDF through the real file adapter; renders one bounded page at a time. */
@RunWith(AndroidJUnit4::class)
class PdfRealDocumentTest {
    @Test fun generatedPdfRendersBoundedPagesAndRestoresUnchangedPosition() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.GET_APP_OPS_STATS")
        val original = try { appOps.unsafeCheckOpNoThrow("android:manage_external_storage",
            Process.myUid(), context.packageName) } finally { automation.dropShellPermissionIdentity() }
        val name = "ModularFilesPdf-${UUID.randomUUID()}"
        val fixture = File(Environment.getExternalStorageDirectory(), name)
        try {
            shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!Environment.isExternalStorageManager() && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(100)
            assertTrue(Environment.isExternalStorageManager())
            val repo = createFilesRepository(context)
            val root = repo.locations().single { it.kind == StorageLocation.Kind.PHONE }
            val folder = repo.createDirectory(root.rootId, name)
            val pdf = repo.createFile(folder.id, "reading.pdf", "application/pdf")
            writePdf(File(fixture, "reading.pdf"), 16, "FILESPDFMARKER")
            PdfDocumentSession.open(context, repo, pdf.id).use { session ->
                assertEquals(16, session.pageCount)
                val bitmap = session.renderPage(10, 4f, maxPixels = 10_000)
                try { assertTrue(bitmap.width * bitmap.height <= 10_000) }
                finally { bitmap.recycle() }
                if (Build.VERSION.SDK_INT >= 35) {
                    assertTrue(session.pageText(10).orEmpty().contains("FILESPDFMARKER"))
                    assertFalse(session.searchPage(10, "FILESPDFMARKER").isNullOrEmpty())
                }
                session.rememberPosition(10, 1.5f)
            }
            PdfDocumentSession.open(context, repo, pdf.id).use { reopened ->
                assertEquals(10, reopened.readingPosition()?.pageIndex)
                assertEquals(1.5f, reopened.readingPosition()?.zoom)
            }
            writePdf(File(fixture, "reading.pdf"), 1, "CHANGED")
            PdfDocumentSession.open(context, repo, pdf.id).use { changed ->
                assertEquals(1, changed.pageCount)
                assertNull("New document revision must not reuse old position", changed.readingPosition())
            }
            val corrupt = repo.createFile(folder.id, "corrupt.pdf", "application/pdf")
            File(fixture, "corrupt.pdf").writeText("not PDF data")
            assertTrue(runCatching { PdfDocumentSession.open(context, repo, corrupt.id) }.isFailure)
        } finally {
            if (fixture.name == name && fixture.parentFile?.canonicalFile ==
                Environment.getExternalStorageDirectory().canonicalFile) fixture.deleteRecursively()
            shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE ${modeName(original)}")
        }
    }

    private fun writePdf(file: File, pages: Int, text: String) {
        val document = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                page.canvas.drawText("$text page $index", 32f, 80f, Paint().apply {
                    textSize = 18f
                    isAntiAlias = true
                })
                document.finishPage(page)
            }
            FileOutputStream(file).use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
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
