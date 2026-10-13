package io.github.alexanderwilhelmsenberg.modular.events

import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppProfile
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.composition.CompositionState
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord

object CoreEventDiffs {
    fun modules(
        previous: List<ModuleRecord>,
        current: List<ModuleRecord>,
    ): CoreEvent.ModulesChanged? {
        val previousById =
            previous.associateBy(::moduleKey)
        val currentById =
            current.associateBy(::moduleKey)

        val added = currentById.keys
            .minus(previousById.keys)
            .sorted()
        val removed = previousById.keys
            .minus(currentById.keys)
            .sorted()
        val changed = currentById.keys
            .intersect(previousById.keys)
            .filter { key ->
                currentById[key] != previousById[key]
            }
            .sorted()

        return if (
            added.isEmpty() &&
            removed.isEmpty() &&
            changed.isEmpty()
        ) {
            null
        } else {
            CoreEvent.ModulesChanged(
                addedComponents = added,
                removedComponents = removed,
                changedComponents = changed,
            )
        }
    }

    fun apps(
        previous: AppCatalog,
        current: AppCatalog,
    ): List<CoreEvent> {
        val events = mutableListOf<CoreEvent>()

        entityDiff(
            previous = previous.apps,
            current = current.apps,
            key = { app -> app.key },
        ).takeIf { diff -> !diff.isEmpty }
            ?.let { diff ->
                events += CoreEvent.AppsChanged(
                    catalogGeneration =
                        current.generation,
                    added = diff.added,
                    removed = diff.removed,
                    changed = diff.changed,
                )
            }

        entityDiff(
            previous = previous.profiles,
            current = current.profiles,
            key = { profile ->
                profile.serialNumber
            },
        ).takeIf { diff -> !diff.isEmpty }
            ?.let { diff ->
                events += CoreEvent.ProfilesChanged(
                    catalogGeneration =
                        current.generation,
                    addedSerials = diff.added,
                    removedSerials = diff.removed,
                    changedSerials = diff.changed,
                )
            }

        return events
    }

    fun composition(
        previous: CompositionState,
        current: CompositionState,
    ): CoreEvent.CompositionChanged? {
        val instanceDiff = entityDiff(
            previous = previous.instances,
            current = current.instances,
            key = { instance -> instance.id },
        )
        val contextDiff = entityDiff(
            previous = previous.contexts,
            current = current.contexts,
            key = { context -> context.id },
        )

        return if (
            instanceDiff.isEmpty &&
            contextDiff.isEmpty
        ) {
            null
        } else {
            CoreEvent.CompositionChanged(
                addedInstanceIds =
                    instanceDiff.added,
                removedInstanceIds =
                    instanceDiff.removed,
                changedInstanceIds =
                    instanceDiff.changed,
                addedContextIds =
                    contextDiff.added,
                removedContextIds =
                    contextDiff.removed,
                changedContextIds =
                    contextDiff.changed,
            )
        }
    }

    fun activeContext(
        previous: ActiveContextState,
        current: ActiveContextState,
    ): List<CoreEvent> {
        val events = mutableListOf<CoreEvent>()
        val previousId = previous.context?.id
        val currentId = current.context?.id

        if (previousId != currentId) {
            events += CoreEvent.ActiveContextChanged(
                previousContextId = previousId,
                currentContextId = currentId,
            )
        }

        if (
            currentId != null &&
            healthSignature(previous) !=
            healthSignature(current)
        ) {
            events +=
                CoreEvent.ActiveContextHealthChanged(
                    contextId = currentId,
                    ready = current.isReady,
                    problems = current.problems
                        .map { problem ->
                            ContextProblem(
                                capabilityId =
                                    problem.capability
                                        .wireValue,
                                instanceId =
                                    problem.instanceId,
                                kind = problem.kind,
                            )
                        }
                        .sortedWith(
                            compareBy(
                                { it.capabilityId },
                                { it.instanceId },
                                { it.kind.name },
                            ),
                        ),
                )
        }

        return events
    }

    private fun moduleKey(
        module: ModuleRecord,
    ): String =
        module.component.flattenToString()

    private fun healthSignature(
        state: ActiveContextState,
    ): HealthSignature =
        HealthSignature(
            contextId = state.context?.id,
            ready = state.isReady,
            problems = state.problems
                .map { problem ->
                    Triple(
                        problem.capability.wireValue,
                        problem.instanceId,
                        problem.kind,
                    )
                }
                .sortedWith(
                    compareBy(
                        { it.first },
                        { it.second },
                        { it.third.name },
                    ),
                ),
        )

    private fun <T, K : Comparable<K>> entityDiff(
        previous: List<T>,
        current: List<T>,
        key: (T) -> K,
    ): EntityDiff<K> {
        val previousByKey =
            previous.associateBy(key)
        val currentByKey =
            current.associateBy(key)

        return EntityDiff(
            added = currentByKey.keys
                .minus(previousByKey.keys)
                .sorted(),
            removed = previousByKey.keys
                .minus(currentByKey.keys)
                .sorted(),
            changed = currentByKey.keys
                .intersect(previousByKey.keys)
                .filter { itemKey ->
                    currentByKey[itemKey] !=
                        previousByKey[itemKey]
                }
                .sorted(),
        )
    }

    private data class EntityDiff<K>(
        val added: List<K>,
        val removed: List<K>,
        val changed: List<K>,
    ) {
        val isEmpty: Boolean
            get() =
                added.isEmpty() &&
                    removed.isEmpty() &&
                    changed.isEmpty()
    }

    private data class HealthSignature(
        val contextId: String?,
        val ready: Boolean,
        val problems: List<
            Triple<
                String,
                String,
                io.github.alexanderwilhelmsenberg.modular.composition.ContextResolutionProblemKind
            >
        >,
    )
}
