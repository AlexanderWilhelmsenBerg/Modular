package io.github.alexanderwilhelmsenberg.modular.addon.files.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor

/** Blocking I/O API. Call from an I/O dispatcher, never the main thread. */
interface FilesRepository {
    fun locations(): List<StorageLocation>
    /** Call after ACTION_OPEN_DOCUMENT_TREE succeeds; its returned read/write flags are persisted. */
    fun addSelectedFolder(uri: Uri, grantFlags: Int): StorageLocation
    fun removeSelectedFolder(locationId: String)
    fun list(folderId: String, offset: Int = 0, limit: Int = 100, sort: FileSort = FileSort.NAME, showHidden: Boolean = false): FilePage
    fun get(id: String): FileEntry
    fun search(folderIds: List<String>, filter: SearchFilter, cancel: Cancellation = Cancellation { false }): SearchPage
    fun searchPage(folderIds: List<String>, filter: SearchFilter, offset: Int, limit: Int = 100, cancel: Cancellation = Cancellation { false }): SearchPage
    fun directoryTotal(folderId: String, cancel: Cancellation = Cancellation { false }): DirectoryTotal
    fun cachedDirectoryTotal(folderId: String): DirectoryTotal?
    fun openRead(id: String): ParcelFileDescriptor
    /** Checks the expected fingerprint immediately before opening; provider atomic save varies. */
    fun openWrite(id: String, expectedFingerprint: String): ParcelFileDescriptor
    /** Direct-storage writes stage alongside the original and require an explicit commit after closing the descriptor. */
    fun beginStagedWrite(id: String, expectedFingerprint: String): StagedWrite
    fun commitStagedWrite(token: String): FileEntry
    fun cancelStagedWrite(token: String)
    fun fingerprint(id: String): String
    fun scopedContent(id: String): ScopedContent
    fun createDirectory(parentId: String, name: String): FileEntry
    fun createFile(parentId: String, name: String, mimeType: String): FileEntry
    fun rename(id: String, newName: String): FileEntry
    fun copyOrMove(ids: List<String>, destinationId: String, move: Boolean, collision: CollisionChoice, cancel: Cancellation = Cancellation { false }, progress: Progress = Progress { _, _, _ -> }, replacementPlans: Map<String, DeletionPlan> = emptyMap()): OperationResult
    fun prepareDeletion(ids: List<String>, cancel: Cancellation = Cancellation { false }): DeletionPlan
    /** Refuses incomplete or changed plans. Never falls back to permanent deletion. */
    fun moveToTrash(plan: DeletionPlan, cancel: Cancellation = Cancellation { false }, progress: Progress = Progress { _, _, _ -> }): OperationResult
    fun trashEntries(): List<TrashEntry>
    fun trashSummary(): TrashSummary
    fun restore(ids: List<String>, choices: Map<String, RestoreChoice>, cancel: Cancellation = Cancellation { false }): OperationResult
    fun prepareEmptyTrash(ids: List<String>): EmptyTrashPlan
    fun emptyTrash(plan: EmptyTrashPlan, cancel: Cancellation = Cancellation { false }): OperationResult
    fun history(): List<OperationHistoryEntry>
    fun clearHistory()
    /** Revalidates both sides; supports the latest successful rename, move, or trash action. */
    fun undoLast(): OperationResult
}

private object RepositoryHolder {
    private var instance: AndroidFilesRepository? = null
    private var application: Context? = null
    fun get(context: Context): FilesRepository = synchronized(this) {
        val app = context.applicationContext
        if (application !== app || instance == null) {
            application = app
            instance = AndroidFilesRepository(app)
        }
        requireNotNull(instance)
    }
}

fun createFilesRepository(context: Context): FilesRepository = RepositoryHolder.get(context)
