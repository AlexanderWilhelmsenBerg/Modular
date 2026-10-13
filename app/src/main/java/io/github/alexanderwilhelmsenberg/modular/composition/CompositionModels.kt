package io.github.alexanderwilhelmsenberg.modular.composition

import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId

data class ModuleInstance(
    val id: String,
    val name: String,
    val providerId: String,
    val configurationJson: String = "{}",
)

data class ContextCapabilityBinding(
    val capability: CapabilityId,
    val instanceId: String,
)

data class LauncherContext(
    val id: String,
    val name: String,
    val bindings: List<ContextCapabilityBinding> = emptyList(),
)

data class CompositionState(
    val instances: List<ModuleInstance> = emptyList(),
    val contexts: List<LauncherContext> = emptyList(),
    val activeContextId: String? = null,
)

enum class ContextResolutionProblemKind {
    MISSING_INSTANCE,
    PROVIDER_UNAVAILABLE,
    PROVIDER_AMBIGUOUS,
}

data class ContextResolutionProblem(
    val capability: CapabilityId,
    val instanceId: String,
    val kind: ContextResolutionProblemKind,
    val detail: String,
)

data class ResolvedContextBinding(
    val capability: CapabilityId,
    val instance: ModuleInstance,
    val route: CapabilityRoute,
)

data class ActiveContextState(
    val context: LauncherContext? = null,
    val resolvedBindings: List<ResolvedContextBinding> = emptyList(),
    val problems: List<ContextResolutionProblem> = emptyList(),
) {
    val isReady: Boolean
        get() = context != null && problems.isEmpty()

    fun routeFor(capability: CapabilityId): CapabilityRoute? =
        resolvedBindings
            .firstOrNull { it.capability == capability }
            ?.route

    companion object {
        val NONE = ActiveContextState()
    }
}
