package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.app.Application
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.createFilesRepository

class FilesApplication : Application() {
    val repository by lazy { createFilesRepository(this) }
    internal val operationLifetime by lazy { AndroidFilesOperationLifetime(this) }
    val pageController by lazy { FilesPageController(this, repository, operationLifetime) }
}
