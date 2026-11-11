package io.github.alexanderwilhelmsenberg.modular.addon.files.storage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** App-private display metadata. Callers must validate every item against storage before acting on it. */
internal class DirectoryListingCache(private val directory: File) {
    data class Snapshot(val atMillis: Long, val entries: List<FileEntry>)

    private val maxFolders = 32
    private val maxEntries = 2_000
    private val maxBytes = 1_000_000L

    @Synchronized fun read(folderId: String, now: Long, maxAgeMillis: Long): Snapshot? {
        val file = fileFor(folderId)
        if (!file.isFile || file.length() !in 1L..maxBytes) return null
        return runCatching {
            val data = JSONObject(file.readText())
            if (data.optInt("version") != 1 || data.optString("folder") != folderId) return null
            val at = data.getLong("at")
            if (at > now || now - at > maxAgeMillis) return null
            val rows = data.getJSONArray("entries")
            if (rows.length() > maxEntries) return null
            Snapshot(at, (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                require(row.getString("parent") == folderId)
                FileEntry(
                    id = row.getString("id"),
                    locationId = row.getString("location"),
                    parentId = folderId,
                    name = row.getString("name"),
                    relativePath = row.getString("path"),
                    isDirectory = row.getBoolean("directory"),
                    mimeType = row.optNullableString("mime"),
                    sizeBytes = row.optNullableLong("size"),
                    modifiedAtMillis = row.optNullableLong("modified"),
                    createdAtMillis = row.optNullableLong("created"),
                    readable = row.getBoolean("readable"),
                    writable = row.getBoolean("writable"),
                )
            })
        }.getOrNull()
    }

    @Synchronized fun write(folderId: String, entries: List<FileEntry>, atMillis: Long) {
        if (entries.size > maxEntries || entries.any { it.parentId != folderId }) {
            invalidate(folderId)
            return
        }
        val rows = JSONArray()
        entries.forEach { entry ->
            rows.put(JSONObject().put("id", entry.id).put("location", entry.locationId)
                .put("parent", folderId).put("name", entry.name).put("path", entry.relativePath)
                .put("directory", entry.isDirectory).put("mime", entry.mimeType)
                .put("size", entry.sizeBytes).put("modified", entry.modifiedAtMillis)
                .put("created", entry.createdAtMillis).put("readable", entry.readable)
                .put("writable", entry.writable))
        }
        val bytes = JSONObject().put("version", 1).put("folder", folderId)
            .put("at", atMillis).put("entries", rows).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) { invalidate(folderId); return }
        if (!directory.isDirectory && !directory.mkdirs()) return
        directory.listFiles { file -> file.name.startsWith("listing-") && file.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
        val target = fileFor(folderId)
        val temp = File.createTempFile("listing-", ".tmp", directory)
        try {
            temp.outputStream().use { stream -> stream.write(bytes); stream.flush() }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val old = directory.listFiles { file -> file.name.endsWith(".json") }.orEmpty()
                .sortedByDescending { it.lastModified() }
            old.drop(maxFolders).forEach { it.delete() }
        } finally { temp.delete() }
    }

    @Synchronized fun invalidate(folderId: String) { fileFor(folderId).delete() }

    @Synchronized fun clear() {
        directory.listFiles { file -> file.name.endsWith(".json") ||
            (file.name.startsWith("listing-") && file.name.endsWith(".tmp")) }?.forEach { it.delete() }
    }

    private fun fileFor(folderId: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(folderId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.json")
    }

    private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.optNullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)
}
