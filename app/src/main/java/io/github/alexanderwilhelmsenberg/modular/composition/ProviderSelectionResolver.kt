package io.github.alexanderwilhelmsenberg.modular.composition

import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute

object ProviderSelectionResolver {
    fun resolve(
        context: LauncherContext,
        instances: List<ModuleInstance>,
        routes: List<CapabilityRoute>,
    ): ActiveContextState {
        val instancesById = instances.associateBy { it.id }
        val resolved = mutableListOf<ResolvedContextBinding>()
        val problems = mutableListOf<ContextResolutionProblem>()

        context.bindings.forEach { binding ->
            val instance = instancesById[binding.instanceId]

            if (instance == null) {
                problems += ContextResolutionProblem(
                    capability = binding.capability,
                    instanceId = binding.instanceId,
                    kind =
                        ContextResolutionProblemKind.MISSING_INSTANCE,
                    detail =
                        "Context references a missing module instance",
                )
                return@forEach
            }

            val matches = routes.filter { route ->
                route.capability == binding.capability &&
                    route.providerId == instance.providerId
            }

            when (matches.size) {
                0 -> {
                    problems += ContextResolutionProblem(
                        capability = binding.capability,
                        instanceId = instance.id,
                        kind =
                            ContextResolutionProblemKind.PROVIDER_UNAVAILABLE,
                        detail =
                            "Provider " + instance.providerId +
                                " is not currently available for " +
                                binding.capability.wireValue,
                    )
                }

                1 -> {
                    val route = matches.single()
                    val validation = runCatching {
                        instance.withValidatedConfiguration(listOf(route))
                    }
                    if (validation.isFailure) {
                        problems += ContextResolutionProblem(
                            capability = binding.capability,
                            instanceId = instance.id,
                            kind = ContextResolutionProblemKind.CONFIGURATION_INVALID,
                            detail = "Saved configuration is incompatible with the current provider schema",
                        )
                    } else {
                        resolved += ResolvedContextBinding(
                            capability = binding.capability,
                            instance = validation.getOrThrow(),
                            route = route,
                        )
                    }
                }

                else -> {
                    problems += ContextResolutionProblem(
                        capability = binding.capability,
                        instanceId = instance.id,
                        kind =
                            ContextResolutionProblemKind.PROVIDER_AMBIGUOUS,
                        detail =
                            "Provider " + instance.providerId +
                                " has multiple live routes for " +
                                binding.capability.wireValue,
                    )
                }
            }
        }

        return ActiveContextState(
            context = context,
            resolvedBindings = resolved
                .sortedBy { it.capability.wireValue },
            problems = problems
                .sortedBy { it.capability.wireValue },
        )
    }
}

