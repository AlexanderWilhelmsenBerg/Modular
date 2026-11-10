package io.github.alexanderwilhelmsenberg.modular.addon.files.archives

import android.content.Context
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.Cancellation
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.CollisionChoice
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FilesRepository
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.Progress
import java.util.UUID

/** Module-owned review session. All methods run on the caller's I/O thread. */
class ArchivePageSupport(context: Context, private val repository: FilesRepository) {
    private val extractor = ArchiveExtractor(context.applicationContext, repository)
    private var sourceId: String? = null
    private var destinationId: String? = null
    private var password: String? = null
    private var trashSource = false
    private val collisionChoices = linkedMapOf<String, CollisionChoice>()
    private var prepared: Review? = null
    private var preparedPassword: String? = null
    private var consumed = false

    data class Review(
        val id: String,
        val inspection: ArchiveInspection,
        val destinationId: String,
        val destinationPath: String,
        val destinationPreview: ArchiveDestinationPreview,
        val collisionChoices: Map<String, CollisionChoice>,
        val collisionFingerprints: Map<String, String>,
        val trashSourceAfterSuccess: Boolean,
        val blockers: List<String>,
    ) {
        val ready: Boolean get() = blockers.isEmpty() && inspection.members.isNotEmpty()
        val fileCount: Int get() = inspection.members.count { !it.directory }
    }

    fun begin(sourceId: String) {
        val source = repository.get(sourceId)
        require(!source.isDirectory) { "Select an archive file" }
        this.sourceId = sourceId
        destinationId = source.parentId
        password = null
        trashSource = false
        collisionChoices.clear()
        invalidate()
    }

    fun sourceId(): String? = sourceId
    fun destinationId(): String? = destinationId
    fun trashSourceAfterSuccess(): Boolean = trashSource
    fun chosenCollisions(): Map<String, CollisionChoice> = collisionChoices.toMap()
    fun currentReview(): Review? = prepared?.takeUnless { consumed }

    fun setDestination(folderId: String) {
        require(repository.get(folderId).isDirectory) { "Destination must be a folder" }
        destinationId = folderId
        collisionChoices.clear()
        invalidate()
    }

    /** Password is held only in memory and omitted from page models, reports, and logs. */
    fun setPassword(value: String?) {
        password = value?.takeIf { it.isNotEmpty() }
        invalidate()
    }

    fun setTrashSourceAfterSuccess(enabled: Boolean) {
        trashSource = enabled
        refreshPrepared()
    }

    fun chooseCollision(memberPath: String, choice: CollisionChoice) {
        prepared?.let { review ->
            require(review.destinationPreview.collisions.any { it.memberPath == memberPath }) {
                "Unknown reviewed collision"
            }
        }
        collisionChoices[memberPath] = choice
        refreshPrepared()
    }

    /** Inspection can be slow. Call after a user action, on the same cancellable worker as extraction. */
    fun prepareReview(cancel: Cancellation = Cancellation { false }): Review {
        val source = requireNotNull(sourceId) { "Choose an archive" }
        val destination = requireNotNull(destinationId) { "Choose a destination" }
        val inspection = extractor.inspect(source, password, cancel)
        require(repository.fingerprint(source) == inspection.sourceFingerprint) { "Archive changed during inspection; scan again" }
        val destinationEntry = repository.get(destination)
        require(destinationEntry.isDirectory) { "Destination is not a folder" }
        val preview = extractor.detectCollisions(inspection, destination)
        val relevant = preview.collisions.map { it.memberPath }.toSet()
        val choices = collisionChoices.filterKeys { it in relevant }.toMap()
        val fingerprints = preview.collisions.associate { it.memberPath to it.existingFingerprint }
        val blockers = buildList {
            addAll(inspection.unsupportedReasons)
            addAll(preview.blockers)
            if (!destinationEntry.writable) add("Destination is read only")
            preview.collisions.forEach { if (it.memberPath !in choices) add("Choose collision action: ${it.memberPath}") }
        }
        val review = Review(UUID.randomUUID().toString(), inspection, destination,
            destinationEntry.relativePath, preview, choices, fingerprints,
            trashSource, blockers)
        prepared = review
        preparedPassword = password
        consumed = false
        return review
    }

    /** Invoke only from the explicit reviewed Extract action. A review ID is single use. */
    fun executeReviewed(reviewId: String, cancel: Cancellation = Cancellation { false },
                        progress: Progress = Progress { _, _, _ -> }): ArchiveExtractionReport {
        val review = prepared ?: throw IllegalStateException("Archive review expired")
        require(review.id == reviewId && !consumed && review.ready) { "Archive review expired or unresolved" }
        consumed = true
        val reviewedPassword = preparedPassword
        password = null
        preparedPassword = null
        require(repository.fingerprint(review.inspection.source.id) == review.inspection.sourceFingerprint) {
            "Archive changed since review; scan again"
        }
        val currentPreview = extractor.detectCollisions(review.inspection, review.destinationId)
        require(currentPreview.blockers.isEmpty()) { "Destination changed; review again" }
        val reviewedCollisions = review.destinationPreview.collisions.associateBy { it.memberPath }
        val currentCollisions = currentPreview.collisions.associateBy { it.memberPath }
        require(reviewedCollisions.keys == currentCollisions.keys) { "Destination collisions changed; review again" }
        for ((path, collision) in currentCollisions) {
            require(collision.existing.id == reviewedCollisions[path]?.existing?.id &&
                repository.fingerprint(collision.existing.id) == review.collisionFingerprints[path]) {
                "Destination item changed; review again"
            }
        }
        return extractor.extract(review.inspection, review.destinationPreview, review.destinationId,
            review.collisionChoices, reviewedPassword, review.trashSourceAfterSuccess, cancel, progress)
    }

    fun close() {
        sourceId = null
        destinationId = null
        password = null
        collisionChoices.clear()
        invalidate()
    }

    private fun invalidate() {
        prepared = null
        preparedPassword = null
        consumed = false
    }

    private fun refreshPrepared() {
        val review = prepared ?: return
        if (consumed) { invalidate(); return }
        val paths = review.destinationPreview.collisions.map { it.memberPath }.toSet()
        val choices = collisionChoices.filterKeys { it in paths }.toMap()
        prepared = review.copy(
            id = UUID.randomUUID().toString(),
            collisionChoices = choices,
            trashSourceAfterSuccess = trashSource,
            blockers = review.blockers.filterNot { it.startsWith("Choose collision action: ") } +
                paths.filterNot { it in choices }.map { "Choose collision action: $it" }
        )
    }
}
