package io.github.alexanderwilhelmsenberg.modular.addon.files.archives

import android.content.Context
import android.system.OsConstants
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.Cancellation
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.CollisionChoice
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FileEntry
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FileOutcome
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FilesRepository
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.Progress
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.StorageLocation
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class ArchiveFormat { ZIP, SEVEN_Z, RAR, TAR, TAR_GZ }
data class ArchiveMember(val path: String, val sizeBytes: Long?, val directory: Boolean, val encrypted: Boolean)
data class ArchiveInspection(
    val source: FileEntry,
    val format: ArchiveFormat,
    val members: List<ArchiveMember>,
    val estimatedOutputBytes: Long?,
    val unsupportedReasons: List<String>,
    val sourceFingerprint: String,
)
data class ArchiveCollision(val memberPath: String, val existing: FileEntry, val existingFingerprint: String)
data class ArchiveDestinationPreview(val collisions: List<ArchiveCollision>, val blockers: List<String>, val availableBytes: Long?)
data class ArchiveExtractionReport(
    val sourceId: String,
    val destinationId: String,
    val outcomes: List<FileOutcome>,
    val createdIds: List<String>,
    val complete: Boolean,
    val sourceMovedToTrash: Boolean,
    val sourceCleanupMessage: String?,
)

/** Pure path validation; archive paths are never interpreted as filesystem paths. */
object ArchivePathPolicy {
    fun segments(raw: String): List<String> {
        require(raw.isNotBlank() && raw.length <= 4096 && !raw.contains('\u0000')) { "Invalid archive path" }
        require(!raw.startsWith('/') && !raw.startsWith('\\') && !Regex("^[A-Za-z]:").containsMatchIn(raw)) { "Absolute archive path" }
        val parts = raw.replace('\\', '/').trimEnd('/').split('/')
        require(parts.isNotEmpty() && parts.size <= 64)
        require(parts.all { it.isNotBlank() && it != "." && it != ".." && it.length <= 255 && !it.contains(':') }) { "Archive path escapes destination" }
        return parts
    }
}

/** Blocking extraction. A complete source copy is staged privately to support seekable readers. */
class ArchiveExtractor(private val context: Context, private val repository: FilesRepository) {
    fun inspect(sourceId: String, password: String? = null, cancel: Cancellation = Cancellation { false }): ArchiveInspection {
        val source = repository.get(sourceId)
        val format = format(source.name)
        val fingerprint = repository.fingerprint(sourceId)
        return withStagedSource(sourceId, cancel) { staged -> inspectStaged(source, format, fingerprint, staged, password, cancel) }
    }

    /** Read-only preflight for a review screen. Every file collision must receive an explicit choice. */
    fun detectCollisions(inspection: ArchiveInspection, destinationId: String): ArchiveDestinationPreview {
        val destination = repository.get(destinationId)
        require(destination.isDirectory) { "Destination is not a folder" }
        val collisions = mutableListOf<ArchiveCollision>()
        val blockers = mutableListOf<String>()
        for (member in inspection.members) {
            val segments = runCatching { ArchivePathPolicy.segments(member.path) }.getOrNull()
            if (segments == null) { blockers += member.path; continue }
            var parentId: String? = destinationId
            for (segment in segments.dropLast(1)) {
                val found = parentId?.let { findChild(it, segment) }
                if (found == null) { parentId = null; break }
                if (!found.isDirectory) { blockers += "File blocks folder: ${member.path}"; parentId = null; break }
                parentId = found.id
            }
            if (parentId == null) continue
            val existing = findChild(parentId, segments.last()) ?: continue
            if (member.directory) {
                if (!existing.isDirectory) blockers += "File blocks folder: ${member.path}"
            } else if (existing.isDirectory) blockers += "Folder blocks file: ${member.path}"
            else collisions += ArchiveCollision(member.path, existing, repository.fingerprint(existing.id))
        }
        val free = repository.locations().firstOrNull { it.id == destination.locationId }?.freeBytes
        if (free != null && inspection.estimatedOutputBytes != null && inspection.estimatedOutputBytes > free)
            blockers += "Insufficient destination space"
        return ArchiveDestinationPreview(collisions, blockers, free)
    }

    /** Caller must display the source identity and collision choices before invoking this operation. */
    fun extract(
        reviewed: ArchiveInspection,
        reviewedDestination: ArchiveDestinationPreview,
        destinationId: String,
        collisions: Map<String, CollisionChoice> = emptyMap(),
        password: String? = null,
        moveSourceToTrashAfterSuccess: Boolean = false,
        cancel: Cancellation = Cancellation { false },
        progress: Progress = Progress { _, _, _ -> },
    ): ArchiveExtractionReport {
        val sourceId = reviewed.source.id
        val source = repository.get(sourceId)
        val format = format(source.name)
        val fingerprint = repository.fingerprint(sourceId)
        val outcomes = mutableListOf<FileOutcome>()
        val created = mutableListOf<String>()
        var cleanupMessage: String? = null
        var sourceTrashed = false
        try {
            withStagedSource(sourceId, cancel) { staged ->
                val inspection = inspectStaged(source, format, fingerprint, staged, password, cancel)
                require(fingerprint == reviewed.sourceFingerprint && inspection.format == reviewed.format && inspection.members == reviewed.members) {
                    "Archive changed since review; inspect it again"
                }
                if (inspection.unsupportedReasons.isNotEmpty()) throw IllegalArgumentException(inspection.unsupportedReasons.joinToString("; "))
                val destinationNow = detectCollisions(inspection, destinationId)
                require(destinationNow.blockers.isEmpty() && destinationNow.collisions == reviewedDestination.collisions) {
                    "Destination changed since review; inspect collisions again"
                }
                require(destinationNow.collisions.all { collisions.containsKey(it.memberPath) }) { "Every collision needs a choice" }
                val destination = repository.get(destinationId)
                require(destination.isDirectory && destination.writable) { "Destination is not a writable folder" }
                val knownTotal = inspection.estimatedOutputBytes
                val available = repository.locations().firstOrNull { it.id == destination.locationId }?.freeBytes
                if (available != null && knownTotal != null && knownTotal > available) throw IllegalStateException("Insufficient destination space")
                val dirs = mutableMapOf<List<String>, String>(emptyList<String>() to destinationId)
                var writtenBytes = 0L
                withReader(staged, password) { reader ->
                    while (true) {
                        if (cancel.isCanceled()) throw ExtractionCanceled()
                        val nativeEntry = Archive.readNextHeader(reader)
                        if (nativeEntry == 0L) break
                        val name = ArchiveEntry.pathnameUtf8(nativeEntry) ?: throw IllegalArgumentException("Archive entry has no UTF-8 path")
                        val parts = ArchivePathPolicy.segments(name)
                        val type = ArchiveEntry.filetype(nativeEntry)
                        val isDir = type == OsConstants.S_IFDIR || name.endsWith('/')
                        if (type != OsConstants.S_IFREG && !isDir) throw IllegalArgumentException("Link or special entry is not extractable: $name")
                        val parent = ensureDirectories(parts.dropLast(1), dirs, created)
                        val existing = findChild(parent, parts.last())
                        if (isDir) {
                            if (existing != null && !existing.isDirectory) throw IllegalStateException("File blocks folder: $name")
                            val folder = existing ?: repository.createDirectory(parent, parts.last()).also { created += it.id }
                            dirs[parts] = folder.id
                            Archive.readDataSkip(reader)
                            outcomes += FileOutcome(name, FileOutcome.Status.SUCCESS, resultingId = folder.id)
                        } else {
                            val choice = collisions[name]
                            if (existing != null && choice == null) throw IllegalStateException("Collision requires a choice: $name")
                            if (existing != null && choice == CollisionChoice.SKIP) {
                                Archive.readDataSkip(reader)
                                outcomes += FileOutcome(name, FileOutcome.Status.SKIPPED, "Existing item retained")
                                continue
                            }
                            if (existing != null && existing.isDirectory) throw IllegalStateException("Folder blocks file: $name")
                            if (existing != null && choice == CollisionChoice.REPLACE) {
                                val reviewedCollision = reviewedDestination.collisions.firstOrNull { it.memberPath == name }
                                require(reviewedCollision?.existing?.id == existing.id && repository.fingerprint(existing.id) == reviewedCollision.existingFingerprint) {
                                    "Replacement changed since review: $name"
                                }
                                val plan = repository.prepareDeletion(listOf(existing.id), cancel)
                                require(plan.complete) { "Cannot safely replace existing item: $name" }
                                val result = repository.moveToTrash(plan, cancel)
                                require(result.outcomes.all { it.status == FileOutcome.Status.SUCCESS }) { "Cannot move replaced item to trash: $name" }
                            }
                            val newName = if (existing != null && choice == CollisionChoice.KEEP_BOTH) uniqueName(parent, parts.last()) else parts.last()
                            val output = repository.createFile(parent, newName, "application/octet-stream")
                            created += output.id
                            val digest = MessageDigest.getInstance("SHA-256")
                            val selectedFolder = repository.locations().firstOrNull { it.id == output.locationId }?.kind == StorageLocation.Kind.SELECTED_FOLDER
                            val stagedWrite = if (selectedFolder) null else repository.beginStagedWrite(output.id, repository.fingerprint(output.id))
                            try {
                                (stagedWrite?.descriptor ?: repository.openWrite(output.id, repository.fingerprint(output.id))).use { descriptor ->
                                    FileOutputStream(descriptor.fileDescriptor).use { stream ->
                                        val buffer = ByteBuffer.allocateDirect(64 * 1024)
                                        while (true) {
                                            if (cancel.isCanceled()) throw ExtractionCanceled()
                                            buffer.clear()
                                            Archive.readData(reader, buffer)
                                            buffer.flip()
                                            if (!buffer.hasRemaining()) break
                                            val bytes = ByteArray(buffer.remaining())
                                            buffer.get(bytes)
                                            writtenBytes += bytes.size
                                            require(writtenBytes <= MAX_OUTPUT_BYTES) { "Archive expansion exceeds limit" }
                                            digest.update(bytes)
                                            stream.write(bytes)
                                            progress.onProgress(writtenBytes, knownTotal, name)
                                        }
                                        stream.flush()
                                        stream.fd.sync()
                                    }
                                }
                                stagedWrite?.let { repository.commitStagedWrite(it.token) }
                            } catch (error: Exception) { stagedWrite?.let { repository.cancelStagedWrite(it.token) }; throw error }
                            val expectedSize = if (ArchiveEntry.sizeIsSet(nativeEntry)) ArchiveEntry.size(nativeEntry) else null
                            val actual = verifyOutput(output.id, digest.digest(), expectedSize)
                            require(actual) { "Output verification failed: $name" }
                            outcomes += FileOutcome(name, FileOutcome.Status.SUCCESS, resultingId = output.id)
                        }
                    }
                }
            }
            if (cancel.isCanceled()) throw ExtractionCanceled()
            if (repository.fingerprint(sourceId) != fingerprint) {
                cleanupMessage = "Archive changed during extraction; source retained"
            } else if (moveSourceToTrashAfterSuccess && outcomes.isNotEmpty() && outcomes.none { it.status != FileOutcome.Status.SUCCESS }) {
                val plan = repository.prepareDeletion(listOf(sourceId), cancel)
                if (plan.complete && repository.fingerprint(sourceId) == fingerprint) {
                    val result = repository.moveToTrash(plan, cancel)
                    sourceTrashed = result.outcomes.all { it.status == FileOutcome.Status.SUCCESS }
                    if (!sourceTrashed) cleanupMessage = "Archive could not be moved to trash"
                } else cleanupMessage = "Archive changed or trash is unavailable; source retained"
            } else if (moveSourceToTrashAfterSuccess) cleanupMessage = "Skipped entries; archive retained"
        } catch (error: Exception) {
            outcomes += FileOutcome(sourceId, if (error is ExtractionCanceled) FileOutcome.Status.CANCELED else FileOutcome.Status.FAILED, error.message)
            cleanupMessage = "Archive retained; review newly created output before cleanup"
        }
        val complete = outcomes.isNotEmpty() && outcomes.all { it.status == FileOutcome.Status.SUCCESS }
        return ArchiveExtractionReport(sourceId, destinationId, outcomes, created, complete, sourceTrashed, cleanupMessage)
    }

    private fun inspectStaged(source: FileEntry, format: ArchiveFormat, fingerprint: String, staged: File, password: String?, cancel: Cancellation): ArchiveInspection {
        val members = mutableListOf<ArchiveMember>()
        val problems = mutableListOf<String>()
        var total = 0L
        var sizesKnown = true
        val paths = mutableSetOf<List<String>>()
        withReader(staged, password) { reader ->
            var checkedFormat = false
            while (true) {
                if (cancel.isCanceled()) throw ExtractionCanceled()
                val entry = Archive.readNextHeader(reader)
                if (!checkedFormat) {
                    require(matchesFormat(reader, format)) { "Archive content does not match ${format.name} format" }
                    checkedFormat = true
                }
                if (entry == 0L) break
                require(members.size < MAX_ENTRIES) { "Archive has too many entries" }
                val name = ArchiveEntry.pathnameUtf8(entry) ?: throw IllegalArgumentException("Archive entry has no UTF-8 path")
                val segments = runCatching { ArchivePathPolicy.segments(name) }.getOrElse { problems += "$name: ${it.message}"; emptyList() }
                if (segments.isNotEmpty() && !paths.add(segments)) problems += "Duplicate archive entry: $name"
                val type = ArchiveEntry.filetype(entry)
                val directory = type == OsConstants.S_IFDIR || name.endsWith('/')
                if (type != OsConstants.S_IFREG && !directory) problems += "Link or special entry: $name"
                val size = if (ArchiveEntry.sizeIsSet(entry)) ArchiveEntry.size(entry) else null
                if (size == null || size < 0) sizesKnown = false else {
                    total += size
                    if (total > MAX_OUTPUT_BYTES) problems += "Archive expansion exceeds limit"
                }
                members += ArchiveMember(name, size, directory, ArchiveEntry.isEncrypted(entry))
                Archive.readDataSkip(reader)
            }
        }
        val siblingNames = source.parentId?.let { parent ->
            buildSet {
                var offset = 0
                do {
                    val page = repository.list(parent, offset = offset, limit = 200, showHidden = true)
                    addAll(page.entries.map { it.name.lowercase() })
                    offset = page.nextOffset ?: break
                } while (true)
            }
        } ?: emptySet()
        val lowerName = source.name.lowercase()
        if (format == ArchiveFormat.RAR && (Regex("""\.part\d+\.rar$""").containsMatchIn(lowerName) ||
                    siblingNames.any { it == lowerName.removeSuffix(".rar") + ".r00" }))
            problems += "Multipart RAR requires all volumes and is unsupported"
        if (format == ArchiveFormat.ZIP && siblingNames.any { it == lowerName.removeSuffix(".zip") + ".z01" })
            problems += "Split ZIP requires all volumes and is unsupported"
        if (staged.length() > 0 && total / staged.length() > MAX_EXPANSION_RATIO) problems += "Archive expansion ratio exceeds limit"
        return ArchiveInspection(source, format, members, if (sizesKnown) total else null, problems, fingerprint)
    }

    private fun matchesFormat(reader: Long, expected: ArchiveFormat): Boolean {
        val actual = Archive.format(reader) and Archive.FORMAT_BASE_MASK
        val expectedBase = when (expected) {
            ArchiveFormat.ZIP -> actual == Archive.FORMAT_ZIP
            ArchiveFormat.SEVEN_Z -> actual == Archive.FORMAT_7ZIP
            ArchiveFormat.RAR -> actual == Archive.FORMAT_RAR || actual == Archive.FORMAT_RAR_V5
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ -> actual == Archive.FORMAT_TAR
        }
        if (!expectedBase) return false
        if (expected == ArchiveFormat.TAR_GZ) return (0 until Archive.filterCount(reader)).any { Archive.filterCode(reader, it) == Archive.FILTER_GZIP }
        return true
    }

    private inline fun <T> withReader(staged: File, password: String?, block: (Long) -> T): T {
        val reader = Archive.readNew()
        try {
            Archive.readSupportFilterAll(reader)
            Archive.readSupportFormatAll(reader)
            password?.let { Archive.readAddPassphrase(reader, it.toByteArray(StandardCharsets.UTF_8)) }
            Archive.readOpenFileName(reader, staged.absolutePath.toByteArray(StandardCharsets.UTF_8), 64 * 1024L)
            return block(reader)
        } finally { Archive.readFree(reader) }
    }

    private inline fun <T> withStagedSource(id: String, cancel: Cancellation, block: (File) -> T): T {
        val stage = File.createTempFile("archive-", ".stage", context.cacheDir)
        try {
            repository.openRead(id).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    FileOutputStream(stage).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            if (cancel.isCanceled()) throw ExtractionCanceled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= MAX_ARCHIVE_BYTES) { "Archive exceeds staging limit" }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
            }
            return block(stage)
        } finally { stage.delete() }
    }

    private fun ensureDirectories(parts: List<String>, dirs: MutableMap<List<String>, String>, created: MutableList<String>): String {
        var path = emptyList<String>()
        var parent = dirs.getValue(path)
        for (part in parts) {
            path = path + part
            val cached = dirs[path]
            if (cached != null) { parent = cached; continue }
            val existing = findChild(parent, part)
            require(existing == null || existing.isDirectory) { "File blocks archive folder: $part" }
            val folder = existing ?: repository.createDirectory(parent, part).also { created += it.id }
            parent = folder.id
            dirs[path] = parent
        }
        return parent
    }

    private fun findChild(parent: String, name: String): FileEntry? {
        var offset = 0
        do {
            val page = repository.list(parent, offset = offset, limit = 200, showHidden = true)
            page.entries.firstOrNull { it.name == name }?.let { return it }
            offset = page.nextOffset ?: break
        } while (true)
        return null
    }

    private fun uniqueName(parent: String, name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (number in 1..1000) {
            val proposed = "$base ($number)$ext"
            if (findChild(parent, proposed) == null) return proposed
        }
        throw IllegalStateException("No available name for $name")
    }

    private fun verifyOutput(id: String, expectedDigest: ByteArray, expectedSize: Long?): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        var actualSize = 0L
        repository.openRead(id).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    actualSize += count
                    digest.update(buffer, 0, count)
                }
            }
        }
        return (expectedSize == null || actualSize == expectedSize) && digest.digest().contentEquals(expectedDigest)
    }

    companion object {
        const val MAX_ENTRIES = 10_000
        const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_OUTPUT_BYTES = 100L * 1024 * 1024 * 1024
        const val MAX_EXPANSION_RATIO = 1_000L

        fun format(name: String): ArchiveFormat = when {
            name.endsWith(".tar.gz", true) || name.endsWith(".tgz", true) -> ArchiveFormat.TAR_GZ
            name.endsWith(".tar", true) -> ArchiveFormat.TAR
            name.endsWith(".zip", true) -> ArchiveFormat.ZIP
            name.endsWith(".7z", true) -> ArchiveFormat.SEVEN_Z
            name.endsWith(".rar", true) -> ArchiveFormat.RAR
            else -> throw UnsupportedOperationException("Unsupported archive format")
        }
    }
}

private class ExtractionCanceled : RuntimeException("Extraction canceled")
