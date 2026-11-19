package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FileOutcome
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.Cancellation
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
    @Test fun trashNeverReadsPayloadAndMetadataChecksStillRejectReplacedOrEditedFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ModularIdentity-${UUID.randomUUID()}"
        val fixture = File(Environment.getExternalStorageDirectory(), name)
        val reads = java.util.Collections.synchronizedList(mutableListOf<String>())
        val repository = io.github.alexanderwilhelmsenberg.modular.addon.files.storage.AndroidFilesRepository(context) { reads.add(it) }
        try {
            val root = repository.locations().single { it.kind == StorageLocation.Kind.PHONE }
            val directory = repository.createDirectory(root.rootId, name)
            val file = repository.createFile(directory.id, "payload.bin", "application/octet-stream")
            val local = File(fixture, "payload.bin").apply { writeText("original") }
            val modified = local.lastModified()
            val editedPlan = repository.prepareDeletion(listOf(file.id))
            Thread.sleep(1_100) // Also exercises filesystems with only whole-second ctime.
            local.writeText("changed!") // Same length; restoring mtime must not hide the edit.
            assertTrue(local.setLastModified(modified))
            assertTrue(repository.moveToTrash(editedPlan).outcomes.all { it.status != FileOutcome.Status.SUCCESS })
            assertTrue(local.exists())
            val replacedPlan = repository.prepareDeletion(listOf(file.id))
            val replacement = File(fixture, "replacement.bin").apply { writeText("replaced"); setLastModified(modified) }
            java.nio.file.Files.move(replacement.toPath(), local.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertTrue(repository.moveToTrash(replacedPlan).outcomes.all { it.status != FileOutcome.Status.SUCCESS })
            val valid = repository.prepareDeletion(listOf(file.id))
            val result = repository.moveToTrash(valid)
            assertTrue(result.outcomes.toString(), result.outcomes.all { it.status == FileOutcome.Status.SUCCESS })
            val ids = result.outcomes.mapNotNull { it.resultingId }
            val empty = repository.emptyTrash(repository.prepareEmptyTrash(ids))
            assertTrue(empty.outcomes.toString(), empty.outcomes.all { it.status == FileOutcome.Status.SUCCESS })
            assertFalse(reads.toString(), reads.any { it == "payload.bin" || it.endsWith(".payload") })
        } finally {
            val own = repository.trashEntries().filter { it.originalPath.startsWith("$name/") }
            if (own.isNotEmpty()) repository.emptyTrash(repository.prepareEmptyTrash(own.map { it.id }))
            fixture.deleteRecursively()
        }
    }

    @Test fun cleanerShowsCancellableProgressAndSafelyKeepsTheHighestCopyWithAYearInItsName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ModularFilesCleaner-${UUID.randomUUID()}"
        val fixture = File(Environment.getExternalStorageDirectory(), name)
        val reads = java.util.Collections.synchronizedList(mutableListOf<String>())
        val repository = io.github.alexanderwilhelmsenberg.modular.addon.files.storage.AndroidFilesRepository(context) { reads.add(it) }
        try {
            val root = repository.locations().single { it.kind == StorageLocation.Kind.PHONE }
            val directory = repository.createDirectory(root.rootId, name)
            val labels = listOf("guide (2017).txt", "guide (2017)(3).txt", "guide (2017)(18).txt")
            val entries = labels.map { repository.createFile(directory.id, it, "text/plain") }
            labels.forEachIndexed { index, label ->
                File(fixture, label).apply {
                    writeBytes(ByteArray(256 * 1024) { (index + 1).toByte() })
                    assertTrue(setLastModified(1_700_000_000_000L + index * 10_000L))
                }
            }
            val canceled = java.util.concurrent.atomic.AtomicBoolean(false)
            var observed = 0L
            assertTrue(runCatching { repository.fingerprint(entries.last().id, Cancellation { canceled.get() },
                Progress { done, _, _ -> observed = done; if (done > 0) canceled.set(true) }) }.isFailure)
            assertTrue(observed > 0)
            assertTrue(File(fixture, labels.last()).exists())
            reads.clear()
            val options = io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner.CleanerEngine.Options(
                directory.id, true, emptySet(), emptySet(), 512L * 1024 * 1024, 90,
                java.time.Instant.now(), setOf(io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner.CleanerEngine.Category.NUMBERED_COPY))
            val adapter = io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner.CleanerRepositoryAdapter(repository, context)
            val analysis = adapter.analyze(adapter.snapshot(directory.id, progressiveOptions = options), options)
            val group = analysis.groups().single()
            assertFalse(group.requiresKeeperChoice())
            assertEquals(entries.last().id, group.suggestedKeeperId())
            var progressCalls = 0
            val progress = Progress { _, _, _ -> progressCalls++ }
            val plan = adapter.prepareReview(analysis, emptyMap(), setOf(group.id()), emptySet(),
                Cancellation { false }, progress)
            assertTrue(plan.blockers().toString(), plan.readyForReview())
            val result = adapter.executeReviewed(plan, Cancellation { false }, progress)
            assertTrue(result.errors.toString(), result.errors.isEmpty())
            assertTrue(progressCalls > 0)
            assertEquals(setOf("guide (2017).txt"), fixture.listFiles()!!.map { it.name }.toSet())
            assertEquals(3.toByte(), File(fixture, "guide (2017).txt").readBytes()[0])
            assertFalse("Numbered cleanup must not read payloads: $reads", reads.any { it in labels || it.endsWith(".payload") })
        } finally {
            val own = repository.trashEntries().filter { it.originalPath.startsWith("$name/") }
            if (own.isNotEmpty()) repository.emptyTrash(repository.prepareEmptyTrash(own.map { it.id }))
            fixture.deleteRecursively()
        }
    }

    @Test fun emptyTrashRemovesReviewedNestedFolderAndKeepsUnreviewedFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ModularFilesEmpty-${UUID.randomUUID()}"
        val fixture = File(Environment.getExternalStorageDirectory(), name)
        val repository = createFilesRepository(context)
        try {
            assertTrue(Environment.isExternalStorageManager())
            val root = repository.locations().single { it.kind == StorageLocation.Kind.PHONE }
            val directory = repository.createDirectory(root.rootId, name)
            val deleting = repository.createDirectory(directory.id, "reviewed")
            val nested = repository.createDirectory(deleting.id, "nested")
            repository.createFile(nested.id, "notes.txt", "text/plain")
            File(fixture, "reviewed/nested/notes.txt").writeText("Nested trash contents")
            val other = repository.createFile(directory.id, "unreviewed.txt", "text/plain")
            for (entry in listOf(deleting, other)) {
                val result = repository.moveToTrash(repository.prepareDeletion(listOf(entry.id)))
                assertTrue(result.outcomes.toString(), result.outcomes.all { it.status == FileOutcome.Status.SUCCESS })
            }
            val records = repository.trashEntries().filter { it.originalPath.startsWith("$name/") }
            // Trash keeps reviewed files and emptied folder markers as separate records.
            val selected = records.filter { it.originalPath == "$name/reviewed" ||
                it.originalPath.startsWith("$name/reviewed/") }
            assertEquals(3, selected.size)
            val selectedIds = selected.map { it.id }
            val retained = records.single { it.originalName == "unreviewed.txt" }
            assertTrue(runCatching { repository.prepareEmptyTrash(selectedIds, Cancellation { true }) }.isFailure)
            assertTrue(repository.trashEntries().map { it.id }.containsAll(selectedIds))
            val plan = repository.prepareEmptyTrash(selectedIds)
            assertTrue(plan.errors.toString(), plan.complete)
            assertTrue(plan.entries.any { it.originalName == "notes.txt" })
            val emptied = repository.emptyTrash(plan)
            assertTrue(emptied.outcomes.toString(), emptied.outcomes.all { it.status == FileOutcome.Status.SUCCESS })
            val remaining = repository.trashEntries()
            assertFalse(remaining.any { it.id in selectedIds })
            assertTrue(remaining.any { it.id == retained.id })
        } finally {
            val own = repository.trashEntries().filter { it.originalPath.startsWith("$name/") }
            if (own.isNotEmpty()) repository.emptyTrash(repository.prepareEmptyTrash(own.map { it.id }))
            fixture.deleteRecursively()
        }
    }

    @Test fun parentScanCachesNestedSizesAndCreatingTextNeverOverwrites() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ModularFilesSizes-${UUID.randomUUID()}"
        val fixture = File(Environment.getExternalStorageDirectory(), name)
        try {
            assertTrue(Environment.isExternalStorageManager())
            val repository = createFilesRepository(context)
            val root = repository.locations().single { it.kind == StorageLocation.Kind.PHONE }
            val directory = repository.createDirectory(root.rootId, name)
            val child = repository.createDirectory(directory.id, "nested")
            val deep = repository.createDirectory(child.id, "deep")
            repository.createFile(directory.id, "root.txt", "text/plain")
            repository.createFile(child.id, "notes.txt", "text/plain")
            repository.createFile(deep.id, "last.txt", "text/plain")
            File(fixture, "root.txt").writeText("aa")
            File(fixture, "nested/notes.txt").writeText("abc")
            File(fixture, "nested/deep/last.txt").writeText("defg")
            assertTrue(runCatching { repository.createFile(child.id, "notes.txt", "text/plain") }.isFailure)
            assertEquals("abc", File(fixture, "nested/notes.txt").readText())
            val total = repository.directoryTotal(directory.id)
            assertTrue(total.complete)
            assertEquals(9L, total.bytes)
            assertEquals(3L, total.fileCount)
            assertEquals(7L, repository.cachedDirectoryTotal(child.id)?.bytes)
            assertEquals(4L, repository.cachedDirectoryTotal(deep.id)?.bytes)
            assertTrue(repository.cachedDirectoryTotal(child.id)?.complete == true)
        } finally { fixture.deleteRecursively() }
    }

    @Test fun reviewedTrashRestoreAndChangedChildStayRecoverable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ModularFilesValidation-${UUID.randomUUID()}"
        val fixture = File(Environment.getExternalStorageDirectory(), name)
        try {
            assertTrue("CI must grant broad storage access before instrumentation",
                Environment.isExternalStorageManager())

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
        }
    }

}
