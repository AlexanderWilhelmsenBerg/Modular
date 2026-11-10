package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.app.AppOpsManager
import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.addon.files.documents.MarkdownDocumentService
import io.github.alexanderwilhelmsenberg.modular.addon.files.documents.MarkdownEncoding
import io.github.alexanderwilhelmsenberg.modular.addon.files.documents.MarkdownLineEnding
import io.github.alexanderwilhelmsenberg.modular.addon.files.documents.MarkdownSaveResult
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.StorageLocation
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.createFilesRepository
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real file draft, external-edit conflict, Save as, and BOM/CRLF preservation. */
@RunWith(AndroidJUnit4::class)
class MarkdownRealDocumentTest {
    @Test fun draftSurvivesServiceRecreationAndExternalEditCannotBeOverwritten() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.GET_APP_OPS_STATS")
        val originalMode = try { appOps.unsafeCheckOpNoThrow("android:manage_external_storage",
            Process.myUid(), context.packageName) } finally { automation.dropShellPermissionIdentity() }
        val name = "ModularFilesMarkdown-${UUID.randomUUID()}"
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
            val document = repo.createFile(folder.id, "note.md", "text/markdown")
            val initial = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "# Before\r\n".toByteArray()
            File(fixture, "note.md").writeBytes(initial)

            val service = MarkdownDocumentService(context, repo)
            val opened = service.open(document.id)
            assertEquals(MarkdownEncoding.UTF8_BOM, opened.encoding)
            assertEquals(MarkdownLineEnding.CRLF, opened.lineEnding)
            service.storeDraft(opened, "# Draft\n")
            val recreated = MarkdownDocumentService(context, createFilesRepository(context)).open(document.id)
            assertEquals("# Draft\n", requireNotNull(recreated.draft).text)

            val external = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "# External\r\n".toByteArray()
            File(fixture, "note.md").writeBytes(external)
            val conflict = service.save(recreated, "# Draft\n")
            assertTrue("External edit must cause a chooser", conflict is MarkdownSaveResult.Conflict)
            assertArrayEquals(external, File(fixture, "note.md").readBytes())
            assertEquals("# Draft\n", MarkdownDocumentService(context, repo).open(document.id).draft?.text)

            val savedCopy = service.saveAs(recreated, "# Draft\n", folder.id, "copy.md")
            assertTrue("Save as failed: $savedCopy", savedCopy is MarkdownSaveResult.Saved)
            assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "# Draft\r\n".toByteArray(), File(fixture, "copy.md").readBytes())
            assertArrayEquals(external, File(fixture, "note.md").readBytes())

            val fresh = service.open(document.id)
            val saved = service.save(fresh, "# Final\n")
            assertTrue("Staged Save failed: $saved", saved is MarkdownSaveResult.Saved)
            assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "# Final\r\n".toByteArray(), File(fixture, "note.md").readBytes())
            service.discardDraft(document.id)
            val copy = repo.list(folder.id).entries.single { it.name == "copy.md" }
            service.discardDraft(copy.id)
        } finally {
            if (fixture.name == name && fixture.parentFile?.canonicalFile ==
                Environment.getExternalStorageDirectory().canonicalFile) fixture.deleteRecursively()
            shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE ${modeName(originalMode)}")
        }
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
