package io.github.alexanderwilhelmsenberg.modular.events

import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.composition.CompositionState
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
import io.github.alexanderwilhelmsenberg.modular.security.ModuleGrantState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Adapts Core's authoritative StateFlows into compact event-bus deltas.
 *
 * The first observed value of each source establishes a baseline. Events are
 * emitted only for later changes. App catalog refresh-in-progress snapshots are
 * skipped so one completed generation produces one coherent delta.
 */
class CoreEventSources(
    private val eventBus: CoreEventBus,
    modules: StateFlow<List<ModuleRecord>>,
    appCatalog: StateFlow<AppCatalog>,
    composition: StateFlow<CompositionState>,
    activeContext: StateFlow<ActiveContextState>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val scope =
        CoroutineScope(SupervisorJob() + dispatcher)

    init {
        scope.launch {
            var previous: List<ModuleRecord>? = null

            modules.collect { current ->
                previous?.let { old ->
                    CoreEventDiffs.modules(
                        previous = old,
                        current = current,
                    )?.let(eventBus::tryPublish)
                }
                previous = current
            }
        }

        scope.launch {
            var previous: AppCatalog? = null

            appCatalog
                .filter { catalog ->
                    catalog.generation > 0 &&
                        !catalog.isRefreshing
                }
                .collect { current ->
                    previous?.let { old ->
                        eventBus.tryPublishAll(
                            CoreEventDiffs.apps(
                                previous = old,
                                current = current,
                            ),
                        )
                    }
                    previous = current
                }
        }

        scope.launch {
            var previous: CompositionState? = null

            composition.collect { current ->
                previous?.let { old ->
                    CoreEventDiffs.composition(
                        previous = old,
                        current = current,
                    )?.let(eventBus::tryPublish)
                }
                previous = current
            }
        }

        scope.launch {
            var previous: ModuleGrantState? = null

            grants.collect { current ->
                previous?.let { old ->
                    CoreEventDiffs.grants(
                        previous = old,
                        current = current,
                    )?.let(eventBus::tryPublish)
                }
                previous = current
            }
        }

        scope.launch {
            var previous: ActiveContextState? = null

            activeContext.collect { current ->
                previous?.let { old ->
                    eventBus.tryPublishAll(
                        CoreEventDiffs.activeContext(
                            previous = old,
                            current = current,
                        ),
                    )
                }
                previous = current
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}
