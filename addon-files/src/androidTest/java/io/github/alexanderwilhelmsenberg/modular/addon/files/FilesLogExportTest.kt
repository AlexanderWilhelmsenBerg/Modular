package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class FilesLogExportTest {
    @Test fun acceptedLogExportFinishesAfterItsActivityIsDestroyed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "event-log-exports").apply { mkdirs() }
        val destination = File(directory, "test-${UUID.randomUUID()}.json")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", destination)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val outcome = AtomicReference<Result<File>>()
        val exporter = FilesLogExporter(context, snapshot = { """{"schemaVersion":1,"entries":[{"message":"complete export"}]}""" },
            executor = Executor { action -> Thread { release.await(5, TimeUnit.SECONDS); action.run() }.start() })
        try {
            ActivityScenario.launch(FilesActivity::class.java).use {
                exporter.export(uri) { result -> outcome.set(result); done.countDown() }
            }
            release.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue(outcome.get().exceptionOrNull()?.stackTraceToString(), outcome.get().isSuccess)
            assertTrue(destination.length() > 0)
            assertEquals("complete export", JSONObject(destination.readText()).getJSONArray("entries")
                .getJSONObject(0).getString("message"))
        } finally { release.countDown(); destination.delete() }
    }

    @Test fun providerReturningAnEmptyFileReportsFailureAndRetainsPrivateJson() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "event-log-exports").apply { mkdirs() }
        val destination = File(directory, "test-${UUID.randomUUID()}.json")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", destination)
        val done = CountDownLatch(1)
        val outcome = AtomicReference<Result<File>>()
        FilesLogExporter(context, snapshot = { """{"entries":[{"message":"retained private copy"}]}""" },
            input = { ByteArrayInputStream(byteArrayOf()) }).export(uri) { result -> outcome.set(result); done.countDown() }
        try {
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue(outcome.get().isFailure)
            assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("modular-files-events-") &&
                runCatching { it.readText().contains("retained private copy") }.getOrDefault(false) })
        } finally { destination.delete() }
    }
}
