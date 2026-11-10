package io.github.alexanderwilhelmsenberg.modular.addon.files.storage

import android.net.Uri
import android.os.ParcelFileDescriptor

/** All IDs are opaque; callers must never construct or edit one. */
data class StorageLocation(
    val id: String,
    val title: String,
    val rootId: String,
    val kind: Kind,
    val available: Boolean,
    val writable: Boolean,
    val capacityBytes: Long?,
    val freeBytes: Long?,
) {
    enum class Kind { SELECTED_FOLDER, PHONE, SD_CARD, USB }
}

data class FileEntry(
    val id: String,
    val locationId: String,
    val parentId: String?,
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val mimeType: String?,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val createdAtMillis: Long?,
    val readable: Boolean,
    val writable: Boolean,
)

data class FilePage(val entries: List<FileEntry>, val nextOffset: Int?, val totalCount: Int?, val complete: Boolean)
enum class FileSort { NAME, MODIFIED, SIZE, TYPE }
data class SearchFilter(
    val nameQuery: String,
    val includeSubfolders: Boolean = true,
    val mimePrefix: String? = null,
    val modifiedAfterMillis: Long? = null,
    val modifiedBeforeMillis: Long? = null,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
)
data class SearchPage(val entries: List<FileEntry>, val includedLocationIds: List<String>, val complete: Boolean, val errors: List<String>, val nextOffset: Int? = null, val totalCount: Int? = null)
data class DirectoryTotal(val bytes: Long, val fileCount: Long, val complete: Boolean, val calculatedAtMillis: Long)

/** A deletion review is immutable. Pass the original plan back for execution. */
data class DeletionPlan(
    val id: String,
    val roots: List<FileEntry>,
    val descendants: List<FileEntry>,
    val totalKnownBytes: Long,
    val fileCount: Long,
    val complete: Boolean,
    val errors: List<String>,
    internal val fingerprints: Map<String, String>,
    internal val directoryChildren: Map<String, Set<String>>,
)
data class FileOutcome(val id: String, val status: Status, val message: String? = null, val resultingId: String? = null, val recoveryIds: List<String> = emptyList(), val resultFingerprint: String? = null) {
    enum class Status { SUCCESS, SKIPPED, FAILED, CHANGED, CANCELED, PARTIAL }
}
data class OperationResult(val operationId: String, val outcomes: List<FileOutcome>)
data class OperationHistoryEntry(val id: String, val kind: String, val atMillis: Long, val outcomes: List<FileOutcome>, val undone: Boolean)
enum class CollisionChoice { SKIP, KEEP_BOTH, REPLACE }
fun interface Cancellation { fun isCanceled(): Boolean }
fun interface Progress { fun onProgress(doneBytes: Long, totalBytes: Long?, currentName: String) }

data class TrashEntry(
    val id: String,
    val locationId: String,
    val originalId: String,
    val originalParentId: String,
    val originalName: String,
    val originalPath: String,
    val trashedAtMillis: Long,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val createdAtMillis: Long?,
    val isDirectory: Boolean,
    val available: Boolean,
)
data class TrashSummary(val fileCount: Long, val emptyFolderCount: Long, val knownBytes: Long, val unavailableEntries: Long) {
    enum class Color { GREY, BLUE, RED }
    val color: Color get() = when {
        fileCount > 20 -> Color.RED
        fileCount > 0 || emptyFolderCount > 0 -> Color.BLUE
        else -> Color.GREY
    }
}
data class EmptyTrashPlan(val entries: List<TrashEntry>, val descendants: List<FileEntry>, val complete: Boolean, val errors: List<String>, internal val fingerprints: Map<String, String>)

/** An explicit chosen destination is required when restoring into another folder. */
data class RestoreChoice(val collision: CollisionChoice, val alternateParentId: String? = null, val replacementPlans: Map<String, DeletionPlan> = emptyMap())

/** URI suitable for a scoped ACTION_VIEW or share grant when the provider supports it. */
data class ScopedContent(val uri: Uri, val mimeType: String?, val grantFlags: Int)
data class StagedWrite(val token: String, val descriptor: ParcelFileDescriptor)
