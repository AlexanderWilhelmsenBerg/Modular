package io.github.alexanderwilhelmsenberg.modular.composition

import kotlinx.coroutines.flow.StateFlow

interface CompositionStateStore : AutoCloseable {
    val state: StateFlow<CompositionState>
    val loadError: StateFlow<String?>

    suspend fun update(
        transform: (CompositionState) -> CompositionState,
    ): CompositionState
}
