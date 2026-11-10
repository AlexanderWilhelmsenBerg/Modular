package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.app.Application
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.createFilesRepository

class FilesApplication : Application() {
    val repository by lazy { createFilesRepository(this) }
    val pageController by lazy { FilesPageController(this, repository) }
}
