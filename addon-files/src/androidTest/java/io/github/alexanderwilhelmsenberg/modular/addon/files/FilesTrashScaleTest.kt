package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.AndroidFilesRepository
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FileOutcome
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.StorageLocation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FilesTrashScaleTest {
    @Test fun hundredsOfTrashEntriesUseFourReadersAndOneDirectoryIndexPerPass() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ModularTrashScale-${UUID.randomUUID()}"
        val fixture = File(Environment.getExternalStorageDirectory(), name)
        val trash = File(Environment.getExternalStorageDirectory(), ".ModularFilesTrash")
        val own = mutableListOf<String>()
        val directoryReads = Collections.synchronizedList(mutableListOf<String>())
        val threads = Collections.synchronizedSet(mutableSetOf<String>())
        val firstReads = AtomicInteger()
        val measure = AtomicBoolean(false)
        val barrier = CyclicBarrier(4)
        val repository = AndroidFilesRepository(context, metadataWorkers = 4,
            directoryReadObserver = { directoryReads.add(it) }, contentReadObserver = { filename ->
                if (measure.get()) {
                    assertTrue("Review must read metadata only: $filename", filename.endsWith(".meta.json"))
                    threads.add(Thread.currentThread().name)
                    if (firstReads.incrementAndGet() <= 4) barrier.await(10, TimeUnit.SECONDS)
                }
            })
        try {
            assertTrue(Environment.isExternalStorageManager())
            val root = repository.locations().single { it.kind == StorageLocation.Kind.PHONE }
            val directory = repository.createDirectory(root.rootId, name)
            val file = repository.createFile(directory.id, "original.txt", "text/plain")
            File(fixture, "original.txt").writeText("fixture")
            val moved = repository.moveToTrash(repository.prepareDeletion(listOf(file.id)))
            assertEquals(FileOutcome.Status.SUCCESS, moved.outcomes.single().status)
            val original = moved.outcomes.single().resultingId!!
            own.add(original)
            val template = File(trash, "$original.meta.json").readText()
            // Build an isolated 335-record fixture without timing per-file setup/journaling.
            repeat(334) { index ->
                val id = UUID.randomUUID().toString()
                own.add(id)
                File(trash, "$id.payload").writeText("fixture")
                val metadata = JSONObject(template).put("id", id).put("payload", "$id.payload")
                    .put("name", "item-$index.txt").put("path", "$name/item-$index.txt")
                File(trash, "$id.meta.json").writeText(metadata.toString())
            }
            directoryReads.clear(); measure.set(true)
            val selected = own.take(334)
            val plan = repository.prepareEmptyTrash(selected)
            measure.set(false)
            assertTrue(plan.errors.toString(), plan.complete)
            assertEquals(334, plan.entries.size)
            assertEquals(334, plan.descendants.size)
            assertEquals("All four metadata readers must overlap", 4, threads.size)
            assertEquals(4L, repository.trashReviewDiagnostics()["workers"])
            assertEquals(334L, repository.trashReviewDiagnostics()["entries"])
            val roots = repository.locations().size
            assertTrue("Trash must be listed once per root: $directoryReads",
                directoryReads.count { it == trash.name } <= roots)
            directoryReads.clear()
            val result = repository.emptyTrash(plan)
            assertEquals(334, result.outcomes.size)
            assertTrue(result.outcomes.toString(), result.outcomes.all { it.status == FileOutcome.Status.SUCCESS })
            assertTrue("Execution must reuse its index: $directoryReads",
                directoryReads.count { it == trash.name } <= roots)
            assertTrue(File(trash, "${own.last()}.payload").exists())
            assertTrue(selected.none { File(trash, "$it.payload").exists() })
        } finally {
            measure.set(false)
            // Never delete trash belonging to another test or user.
            own.forEach { id -> File(trash, "$id.payload").delete(); File(trash, "$id.meta.json").delete() }
            fixture.deleteRecursively()
            repository.trashEntries()
        }
    }
}
