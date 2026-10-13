package io.github.alexanderwilhelmsenberg.modular.security

import kotlinx.coroutines.flow.StateFlow

interface ModuleGrantStateStore : AutoCloseable {
    val state: StateFlow<ModuleGrantState>
    val loadError: StateFlow<String?>

    suspend fun update(
        transform: (ModuleGrantState) -> ModuleGrantState,
    ): ModuleGrantState
}
