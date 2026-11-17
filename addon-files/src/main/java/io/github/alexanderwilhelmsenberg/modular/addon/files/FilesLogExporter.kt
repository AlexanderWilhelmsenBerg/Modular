package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Owns accepted exports outside any Activity or Compose lifecycle. */
internal class FilesLogExporter(
    private val context: Context,
    private val snapshot: () -> String,
    private val executor: Executor = Executors.newSingleThreadExecutor { Thread(it, "files-log-export").apply { isDaemon = true } },
    private val output: (Uri) -> OutputStream? = { context.contentResolver.openOutputStream(it, "wt") },
    private val input: (Uri) -> InputStream? = { context.contentResolver.openInputStream(it) },
) {
    fun stage(): File {
        val json = snapshot()
        require(JSONObject(json).has("entries")) { "The event log snapshot is invalid" }
        val directory = File(context.cacheDir, "event-log-exports").apply { mkdirs() }
        val file = File.createTempFile("modular-files-events-", ".json", directory)
        FileOutputStream(file).use { stream -> stream.write(json.toByteArray(Charsets.UTF_8)); stream.fd.sync() }
        check(file.length() > 0) { "The event log snapshot is empty" }
        directory.listFiles()?.filter { it != file && it.name.startsWith("modular-files-events-") }
            ?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        return file
    }

    fun prepare(completed: (Result<File>) -> Unit) { executor.execute { completed(runCatching(::stage)) } }

    fun export(uri: Uri, completed: (Result<File>) -> Unit) {
        val resolver = context.contentResolver
        val alreadyPersisted = resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        val acquired = !alreadyPersisted && runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION); true
        }.getOrDefault(false)
        executor.execute {
            val result = runCatching {
                val file = stage() // Complete and durable before the destination can be truncated.
                requireNotNull(output(uri)) { "Could not open the log destination" }.use { destination ->
                    file.inputStream().use { it.copyTo(destination) }; destination.flush()
                }
                // A provider that accepts the write but saves an empty file must not report success.
                val readback = try { input(uri) } catch (_: SecurityException) { null }
                readback?.use {
                    check(it.readBytes().contentEquals(file.readBytes())) {
                        "The destination did not retain the full log. The private copy is safe; use Share JSON or choose another location."
                    }
                }
                file
            }
            if (acquired) runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            completed(result)
        }
    }
}
