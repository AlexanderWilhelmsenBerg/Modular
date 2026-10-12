package io.github.alexanderwilhelmsenberg.modular.composition

import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionMutationPolicyTest {
    @Test
    fun existingInstanceCannotBeRetargetedToAnotherProvider() =
        runBlocking {
            val store = FakeCompositionStateStore()
            val instances = InstanceStore(store)

            try {
                val original = instances.create(
                    name = "Personal sort",
                    providerId = PROVIDER_ID,
                ).getOrThrow()

                val result = instances.upsert(
                    original.copy(
                        providerId = "different.provider",
                    ),
                )

                assertTrue(result.isFailure)
                assertEquals(
                    PROVIDER_ID,
                    store.state.value.instances
                        .single()
                        .providerId,
                )
            } finally {
                instances.close()
                store.close()
            }
        }

    @Test
    fun activeContextBindingsCannotChangeWithoutDeactivation() =
        runBlocking {
            val store = FakeCompositionStateStore()
            val routes = MutableStateFlow(
                listOf(alphabeticalSortRoute()),
            )
            val instances = InstanceStore(store)
            val contexts = ContextManager(store, routes)

            try {
                val personal = instances.create(
                    name = "Personal",
                    providerId = PROVIDER_ID,
                ).getOrThrow()
                val work = instances.create(
                    name = "Work",
                    providerId = PROVIDER_ID,
                ).getOrThrow()

                val context = contexts.create(
                    name = "Personal",
                    bindings = listOf(binding(personal.id)),
                ).getOrThrow()

                contexts.activate(context.id).getOrThrow()

                val result = contexts.upsert(
                    context.copy(
                        bindings = listOf(binding(work.id)),
                    ),
                )

                assertTrue(result.isFailure)
                assertEquals(
                    personal.id,
                    store.state.value.contexts
                        .single()
                        .bindings
                        .single()
                        .instanceId,
                )
                assertEquals(
                    context.id,
                    store.state.value.activeContextId,
                )
            } finally {
                contexts.close()
                instances.close()
                store.close()
            }
        }

    @Test
    fun duplicateLiveProviderIdentityIsAmbiguous() {
        val instance = ModuleInstance(
            id = "instance",
            name = "Sort",
            providerId = PROVIDER_ID,
        )
        val context = LauncherContext(
            id = "context",
            name = "Personal",
            bindings = listOf(binding(instance.id)),
        )

        val result = ProviderSelectionResolver.resolve(
            context = context,
            instances = listOf(instance),
            routes = listOf(
                alphabeticalSortRoute(),
                alphabeticalSortRoute(),
            ),
        )

        assertTrue(!result.isReady)
        assertEquals(
            ContextResolutionProblemKind.PROVIDER_AMBIGUOUS,
            result.problems.single().kind,
        )
    }

    private fun binding(
        instanceId: String,
    ) = ContextCapabilityBinding(
        capability = StandardCapabilities.AppsSort,
        instanceId = instanceId,
    )

    private fun alphabeticalSortRoute() =
        CapabilityRoute(
            capability = StandardCapabilities.AppsSort,
            providerKind = CapabilityProviderKind.MODULE,
            providerId = PROVIDER_ID,
        )

    private class FakeCompositionStateStore :
        CompositionStateStore {
        private val mutableState =
            MutableStateFlow(CompositionState())
        private val mutableLoadError =
            MutableStateFlow<String?>(null)

        override val state: StateFlow<CompositionState> =
            mutableState

        override val loadError: StateFlow<String?> =
            mutableLoadError

        override suspend fun update(
            transform: (CompositionState) -> CompositionState,
        ): CompositionState {
            val next = transform(mutableState.value)
            mutableState.value = next
            return next
        }

        override fun close() = Unit
    }

    companion object {
        private const val PROVIDER_ID =
            "modular.reference.sort.alphabetical"
    }
}
