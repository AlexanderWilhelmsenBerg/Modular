package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.app.AppOpsManager
import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.addon.files.archives.ArchivePageSupport
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FileOutcome
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.StorageLocation
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.createFilesRepository
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises libarchive plus real repository writes, verification, and optional source trash. */
@RunWith(AndroidJUnit4::class)
class ArchiveRealStorageTest {
    @Test fun verifiedZipMayTrashSourceButUnsafeOrChangedZipRetainsIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.GET_APP_OPS_STATS")
        val original = try { appOps.unsafeCheckOpNoThrow("android:manage_external_storage",
            Process.myUid(), context.packageName) } finally { automation.dropShellPermissionIdentity() }
        val name = "ModularFilesArchive-${UUID.randomUUID()}"
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
            val destination = repository.createDirectory(folder.id, "output")
            val safe = repository.createFile(folder.id, "safe.zip", "application/zip")
            val alpha = "first exact content\n".toByteArray()
            val beta = "second exact content\n".toByteArray()
            writeZip(File(fixture, "safe.zip"), "alpha.txt" to alpha, "nested/beta.txt" to beta)

            val support = ArchivePageSupport(context, repository)
            support.begin(safe.id)
            support.setDestination(destination.id)
            support.setTrashSourceAfterSuccess(true)
            val reviewed = support.prepareReview()
            assertTrue("Safe ZIP should be reviewable: ${reviewed.blockers}", reviewed.ready)
            val result = support.executeReviewed(reviewed.id)
            assertTrue("Safe extraction failed: ${result.outcomes}", result.complete)
            assertTrue("Reviewed source cleanup did not use trash", result.sourceMovedToTrash)
            assertFalse(File(fixture, "safe.zip").exists())
            assertArrayEquals(alpha, File(fixture, "output/alpha.txt").readBytes())
            assertArrayEquals(beta, File(fixture, "output/nested/beta.txt").readBytes())
            val trashRecord = repository.trashEntries().single { it.originalPath == "$name/safe.zip" }
            assertEquals(FileOutcome.Status.SUCCESS,
                repository.restore(listOf(trashRecord.id), emptyMap()).outcomes.single().status)
            assertTrue(File(fixture, "safe.zip").exists())

            val traversal = repository.createFile(folder.id, "traversal.zip", "application/zip")
            writeZip(File(fixture, "traversal.zip"), "../escape.txt" to "bad".toByteArray())
            support.begin(traversal.id)
            support.setDestination(destination.id)
            support.setTrashSourceAfterSuccess(true)
            val unsafe = support.prepareReview()
            assertFalse("Traversal archive must have blockers", unsafe.ready)
            assertTrue(runCatching { support.executeReviewed(unsafe.id) }.isFailure)
            assertTrue(File(fixture, "traversal.zip").exists())
            assertFalse(File(fixture.parentFile, "escape.txt").exists())

            val corrupt = repository.createFile(folder.id, "corrupt.zip", "application/zip")
            File(fixture, "corrupt.zip").writeText("not a zip")
            support.begin(corrupt.id)
            support.setDestination(destination.id)
            support.setTrashSourceAfterSuccess(true)
            assertTrue("Corrupt archive must fail inspection", runCatching { support.prepareReview() }.isFailure)
            assertTrue(File(fixture, "corrupt.zip").exists())

            val changedDestination = repository.createDirectory(folder.id, "changed-output")
            support.begin(safe.id)
            support.setDestination(changedDestination.id)
            support.setTrashSourceAfterSuccess(true)
            val stale = support.prepareReview()
            assertTrue(stale.ready)
            FileOutputStream(File(fixture, "safe.zip"), true).use { it.write(1) }
            assertTrue("Changed source must invalidate review", runCatching { support.executeReviewed(stale.id) }.isFailure)
            assertTrue(File(fixture, "safe.zip").exists())
        } finally {
            if (fixture.name == name && fixture.parentFile?.canonicalFile ==
                Environment.getExternalStorageDirectory().canonicalFile) fixture.deleteRecursively()
            shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE ${modeName(original)}")
        }
    }

    private fun writeZip(file: File, vararg members: Pair<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            members.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
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
