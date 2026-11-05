package io.github.alexanderwilhelmsenberg.modular.composition

import kotlinx.coroutines.flow.StateFlow

interface CompositionStateStore : AutoCloseable {
    val state: StateFlow<CompositionState>
    val loadError: StateFlow<String?>

    /** Atomically publish the whole transformed state or leave it unchanged.
     * Cancellation at commit/return may leave either complete state. Updates
     * must be serialized; no partial transformation may become observable.
     */
    suspend fun update(
        transform: (CompositionState) -> CompositionState,
    ): CompositionState
}
