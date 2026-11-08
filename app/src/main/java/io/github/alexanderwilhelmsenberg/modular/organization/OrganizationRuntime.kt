package io.github.alexanderwilhelmsenberg.modular.organization

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Coordinates sensitive records with the single atomic organization preference edit. */
class OrganizationRuntime(context: Context, val store: OrganizationStore) {
    val conditions = SavedConditionStore(context)
    val signals = FolderConditionCoordinator(context, conditions)
    private val mutations = Mutex()

    suspend fun initialize() = mutations.withLock {
        val current = store.currentSnapshot()
        // A fallback empty snapshot must never erase the records of an unreadable document.
        if (store.error.value == null) cleanup(current)
    }

    suspend fun update(batch: OrganizationBatch): Result<OrganizationSnapshot> = mutations.withLock {
        val result = store.update(batch)
        // Never delete staged records while a save in this runtime is in progress.
        result.getOrNull()?.let { cleanup(it) }
        result
    }

    suspend fun saveFolder(folder: FolderDefinition, rules: List<FolderCondition>, expectedRevision: Long): Result<Unit> =
        mutations.withLock {
            try {
                require(folder.visibilityMode != FolderVisibilityMode.SMART || rules.any { it.enabled }) {
                    "Add at least one enabled rule for smart visibility"
                }
                val current = store.currentSnapshot()
                if (current.revision != expectedRevision) return@withLock Result.failure(
                    IllegalStateException("Folder settings changed. Reopen them and try again."))
                val refs = withContext(Dispatchers.IO) { conditions.stage(rules) }
                val updated = folder.copy(conditionIds = refs)
                val edit = if (current.folders.any { it.id == folder.id }) OrganizationEdit.UpdateFolder(updated)
                    else OrganizationEdit.CreateFolder(updated)
                val result = store.update(OrganizationBatch(listOf(edit), expectedRevision))
                // Use the proven committed result, never a potentially stale fallback reread.
                result.getOrNull()?.let { cleanup(it) }
                result.map { Unit }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Result.failure(failure)
            }
        }

    fun close() = signals.close()

    private suspend fun cleanup(snapshot: OrganizationSnapshot) = withContext(Dispatchers.IO) {
        // Cleanup is recoverable at startup; it must not turn a committed save into a reported failure.
        runCatching { conditions.reconcile(snapshot.folders.flatMap { it.conditionIds }.toSet()) }
        Unit
    }
}
