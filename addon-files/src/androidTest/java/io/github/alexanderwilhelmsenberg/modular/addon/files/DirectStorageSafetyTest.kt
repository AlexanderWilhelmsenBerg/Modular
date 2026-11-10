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
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FileOutcome
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.CollisionChoice
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.Progress
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.StorageLocation
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.TrashSummary
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.createFilesRepository
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real direct-storage operations, scoped to a unique folder created by this test. */
@RunWith(AndroidJUnit4::class)
class DirectStorageSafetyTest {
    @Test fun reviewedTrashRestoreAndChangedChildStayRecoverable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.GET_APP_OPS_STATS")
        val originalMode = try { appOps.unsafeCheckOpNoThrow(
            "android:manage_external_storage", Process.myUid(), context.packageName,
        ) } finally { automation.dropShellPermissionIdentity() }
        val name = "ModularFilesValidation-${UUID.randomUUID()}"
        val fixture = File(Environment.getExternalStorageDirectory(), name)
        try {
            shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!Environment.isExternalStorageManager() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100)
            }
            assertTrue("Files APK did not receive optional full storage access", Environment.isExternalStorageManager())

            val repository = createFilesRepository(context)
            val root = repository.locations().single { it.kind == StorageLocation.Kind.PHONE }
            assertEquals(0L, repository.trashSummary().fileCount)
            val directory = repository.createDirectory(root.rootId, name)
            assertTrue(fixture.isDirectory)
            val entries = (0 until 21).map { repository.createFile(directory.id, "item-$it.txt", "text/plain") }

            fun trash(ids: List<String>) {
                val plan = repository.prepareDeletion(ids)
                assertTrue("Review must enumerate fixture files", plan.complete && plan.errors.isEmpty())
                val result = repository.moveToTrash(plan)
                assertTrue("Trash failed: ${result.outcomes}", result.outcomes.all {
                    it.status == FileOutcome.Status.SUCCESS
                })
            }
            trash(entries.take(1).map { it.id })
            assertEquals(TrashSummary.Color.BLUE, repository.trashSummary().color)
            assertEquals(1L, repository.trashSummary().fileCount)
            trash(entries.drop(1).take(19).map { it.id })
            assertEquals(20L, repository.trashSummary().fileCount)
            assertEquals(TrashSummary.Color.BLUE, repository.trashSummary().color)
            trash(entries.takeLast(1).map { it.id })
            assertEquals(21L, repository.trashSummary().fileCount)
            assertEquals(TrashSummary.Color.RED, repository.trashSummary().color)

            val one = repository.trashEntries().single { it.originalName == "item-20.txt" }
            val restored = repository.restore(listOf(one.id), emptyMap())
            assertEquals(FileOutcome.Status.SUCCESS, restored.outcomes.single().status)
            assertTrue(File(fixture, "item-20.txt").exists())
            assertEquals(20L, repository.trashSummary().fileCount)
            assertEquals(TrashSummary.Color.BLUE, repository.trashSummary().color)

            val remaining = repository.trashEntries().filter { it.originalPath.startsWith("$name/") }
            assertEquals(20, remaining.size)
            val allRestored = repository.restore(remaining.map { it.id }, emptyMap())
            assertTrue("Restore failed: ${allRestored.outcomes}", allRestored.outcomes.all {
                it.status == FileOutcome.Status.SUCCESS
            })
            assertEquals(0L, repository.trashSummary().fileCount)
            assertEquals(TrashSummary.Color.GREY, repository.trashSummary().color)
            assertEquals(21, fixture.listFiles()?.size)

            val destination = repository.createDirectory(directory.id, "destination")
            val moving = repository.createFile(directory.id, "changing-source.bin", "application/octet-stream")
            val sourceFile = File(fixture, "changing-source.bin")
            FileOutputStream(sourceFile).use { it.write(ByteArray(512 * 1024) { 7 }) }
            var changedDuringCopy = false
            val interruptedMove = repository.copyOrMove(listOf(moving.id), destination.id,
                move = true, collision = CollisionChoice.SKIP,
                progress = Progress { _, _, _ ->
                    if (!changedDuringCopy) {
                        FileOutputStream(sourceFile, true).use { it.write(42) }
                        changedDuringCopy = true
                    }
                })
            assertTrue("Copy callback was never exercised", changedDuringCopy)
            assertFalse("Changed source must not be removed", interruptedMove.outcomes.any {
                it.status == FileOutcome.Status.SUCCESS
            })
            assertTrue("Changed source survived", sourceFile.exists())
            assertFalse("Unverified destination must not be committed", File(fixture, "destination/changing-source.bin").exists())
            assertTrue("Partial copy must be identified", interruptedMove.outcomes.single().message.orEmpty().contains("partial"))
            assertEquals(0L, repository.trashSummary().fileCount)

            val nested = repository.createDirectory(directory.id, "nested")
            val reviewed = repository.createFile(nested.id, "reviewed.txt", "text/plain")
            val frozen = repository.prepareDeletion(listOf(nested.id))
            assertTrue(frozen.complete)
            val late = repository.createFile(nested.id, "new-after-review.txt", "text/plain")
            val rejected = repository.moveToTrash(frozen)
            assertFalse("New child must invalidate the frozen review", rejected.outcomes.any {
                it.status == FileOutcome.Status.SUCCESS
            })
            assertTrue(File(fixture, "nested/reviewed.txt").exists())
            assertTrue(File(fixture, "nested/new-after-review.txt").exists())
            assertEquals(reviewed.id, repository.get(reviewed.id).id)
            assertEquals(late.id, repository.get(late.id).id)
            assertEquals(0L, repository.trashSummary().fileCount)

            val emptyFolder = repository.createDirectory(directory.id, "empty-to-trash")
            val folderTrash = repository.moveToTrash(repository.prepareDeletion(listOf(emptyFolder.id)))
            assertEquals(FileOutcome.Status.SUCCESS, folderTrash.outcomes.single().status)
            val record = repository.trashEntries().single {
                it.originalName == "empty-to-trash" && it.originalPath.startsWith("$name/")
            }
            assertEquals(TrashSummary.Color.BLUE, repository.trashSummary().color)
            val frozenEmpty = repository.prepareEmptyTrash(listOf(record.id))
            val lateTrashChild = File(Environment.getExternalStorageDirectory(),
                ".ModularFilesTrash/${record.id}.folder/late.txt")
            assertTrue(lateTrashChild.createNewFile())
            val refusedEmpty = repository.emptyTrash(frozenEmpty)
            assertEquals(FileOutcome.Status.FAILED, refusedEmpty.outcomes.single().status)
            assertTrue("Permanent delete must leave an unreviewed trash child", lateTrashChild.exists())
            assertTrue(repository.trashEntries().any { it.id == record.id })
            assertTrue(lateTrashChild.delete())
            val refreshedEmpty = repository.emptyTrash(repository.prepareEmptyTrash(listOf(record.id)))
            assertEquals(FileOutcome.Status.SUCCESS, refreshedEmpty.outcomes.single().status)
            assertEquals(TrashSummary.Color.GREY, repository.trashSummary().color)
        } finally {
            // This path contains a UUID generated above and is always a direct child of shared storage.
            if (fixture.name == name && fixture.parentFile?.canonicalFile ==
                Environment.getExternalStorageDirectory().canonicalFile) fixture.deleteRecursively()
            shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE ${modeName(originalMode)}")
        }
    }

    private fun modeName(mode: Int) = when (mode) {
        AppOpsManager.MODE_ALLOWED -> "allow"
        AppOpsManager.MODE_IGNORED -> "ignore"
        AppOpsManager.MODE_ERRORED -> "deny"
        AppOpsManager.MODE_DEFAULT -> "default"
        AppOpsManager.MODE_FOREGROUND -> "foreground"
        else -> error("Unknown original All files access mode: $mode")
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
