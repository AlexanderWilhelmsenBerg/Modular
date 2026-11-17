package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.app.Application
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.createFilesRepository

class FilesApplication : Application() {
    val repository by lazy { createFilesRepository(this) }
    internal val operationLifetime by lazy { AndroidFilesOperationLifetime(this) }
    internal val trashReviewStore by lazy { TrashReviewStore(java.io.File(filesDir, "pending-trash-review.json")) }
    val pageController by lazy { FilesPageController(this, repository, operationLifetime, trashReviewStore)
        .also { it.recoverTrashReview() } }
    internal val logExporter by lazy { FilesLogExporter(this, snapshot = { pageController.exportEventLogJson() }) }
}
