package io.github.alexanderwilhelmsenberg.modular.events

import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.composition.ContextResolutionProblemKind

/**
 * Internal Core event contract.
 *
 * Events are notifications/invalidation deltas, never the authoritative state.
 * Consumers that start late or detect a sequence gap must resync from the
 * owning StateFlow/repository.
 */
sealed interface CoreEvent {
    val type: String
    val version: Int
        get() = 1

    data class ModulesChanged(
        val addedComponents: List<String>,
        val removedComponents: List<String>,
        val changedComponents: List<String>,
    ) : CoreEvent {
        override val type: String =
            "core.modules.changed"
    }

    data class AppsChanged(
        val catalogGeneration: Long,
        val added: List<AppKey>,
        val removed: List<AppKey>,
        val changed: List<AppKey>,
    ) : CoreEvent {
        override val type: String =
            "core.apps.changed"
    }

    data class ProfilesChanged(
        val catalogGeneration: Long,
        val addedSerials: List<Long>,
        val removedSerials: List<Long>,
        val changedSerials: List<Long>,
    ) : CoreEvent {
        override val type: String =
            "core.profiles.changed"
    }

    data class CompositionChanged(
        val addedInstanceIds: List<String>,
        val removedInstanceIds: List<String>,
        val changedInstanceIds: List<String>,
        val addedContextIds: List<String>,
        val removedContextIds: List<String>,
        val changedContextIds: List<String>,
    ) : CoreEvent {
        override val type: String =
            "core.composition.changed"
    }

    data class ActiveContextChanged(
        val previousContextId: String?,
        val currentContextId: String?,
    ) : CoreEvent {
        override val type: String =
            "core.context.active.changed"
    }

    data class ActiveContextHealthChanged(
        val contextId: String,
        val ready: Boolean,
        val problems: List<ContextProblem>,
    ) : CoreEvent {
        override val type: String =
            "core.context.health.changed"
    }
}

data class ContextProblem(
    val capabilityId: String,
    val instanceId: String,
    val kind: ContextResolutionProblemKind,
)

data class CoreEventEnvelope(
    val sequence: Long,
    val event: CoreEvent,
) {
    val schemaVersion: Int
        get() = SCHEMA_VERSION

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

data class CoreEventBusDiagnostics(
    val latestPublishedSequence: Long = 0,
    val latestDeliveredSequence: Long = 0,
    val droppedBeforeDelivery: Long = 0,
)

sealed interface CoreEventContinuity {
    data object First : CoreEventContinuity
    data object Continuous : CoreEventContinuity

    data class Gap(
        val expectedSequence: Long,
        val actualSequence: Long,
    ) : CoreEventContinuity
}

/**
 * Small helper for consumers which want explicit resync-on-gap behavior.
 */
class CoreEventCursor {
    private var lastSequence: Long? = null

    fun observe(
        envelope: CoreEventEnvelope,
    ): CoreEventContinuity {
        val previous = lastSequence
        lastSequence = envelope.sequence

        if (previous == null) {
            return CoreEventContinuity.First
        }

        val expected = previous + 1
        return if (envelope.sequence == expected) {
            CoreEventContinuity.Continuous
        } else {
            CoreEventContinuity.Gap(
                expectedSequence = expected,
                actualSequence = envelope.sequence,
            )
        }
    }

    fun reset() {
        lastSequence = null
    }
}
