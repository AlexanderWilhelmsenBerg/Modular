package io.github.alexanderwilhelmsenberg.modular.apps

import kotlinx.coroutines.flow.StateFlow

interface AppRepository : AutoCloseable {
    val catalog: StateFlow<AppCatalog>

    fun refresh()

    suspend fun launch(key: AppKey): Result<Unit>
}
