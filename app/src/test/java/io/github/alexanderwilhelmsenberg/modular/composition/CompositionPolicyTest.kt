package io.github.alexanderwilhelmsenberg.modular.composition

import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionPolicyTest {
    @Test
    fun personalAndWorkCanUseDistinctInstancesOfSameProvider() =
        runBlocking {
            val store = FakeCompositionStateStore()
            val routes = MutableStateFlow(
                listOf(alphabeticalSortRoute()),
            )
            val instances = InstanceStore(store)
            val contexts = ContextManager(store, routes)

            try {
                val personal = instances.create(
                    name = "Personal sort",
                    providerId = PROVIDER_ID,
                ).getOrThrow()

                val work = instances.create(
                    name = "Work sort",
                    providerId = PROVIDER_ID,
                ).getOrThrow()

                assertNotEquals(personal.id, work.id)

                val personalContext = contexts.create(
                    name = "Personal",
                    bindings = listOf(
                        binding(personal.id),
                    ),
                ).getOrThrow()

                val workContext = contexts.create(
                    name = "Work",
                    bindings = listOf(
                        binding(work.id),
                    ),
                ).getOrThrow()

                val personalActive = contexts.activate(
                    personalContext.id,
                ).getOrThrow()

                assertEquals(
                    personal.id,
                    personalActive.resolvedBindings
                        .single()
                        .instance
                        .id,
                )

                val workActive = contexts.activate(
                    workContext.id,
                ).getOrThrow()

                assertEquals(
                    work.id,
                    workActive.resolvedBindings
                        .single()
                        .instance
                        .id,
                )
                assertEquals(
                    workContext.id,
                    store.state.value.activeContextId,
                )
            } finally {
                contexts.close()
                instances.close()
                store.close()
            }
        }

    @Test
    fun failedActivationDoesNotReplaceCurrentContext() =
        runBlocking {
            val store = FakeCompositionStateStore()
            val routes = MutableStateFlow(
                listOf(alphabeticalSortRoute()),
            )
            val instances = InstanceStore(store)
            val contexts = ContextManager(store, routes)

            try {
                val available = instances.create(
                    name = "Available",
                    providerId = PROVIDER_ID,
                ).getOrThrow()
                val unavailable = instances.create(
                    name = "Unavailable",
                    providerId = "missing.provider",
                ).getOrThrow()

                val activeContext = contexts.create(
                    name = "Active",
                    bindings = listOf(
                        binding(available.id),
                    ),
                ).getOrThrow()
                val brokenContext = contexts.create(
                    name = "Broken",
                    bindings = listOf(
                        binding(unavailable.id),
                    ),
                ).getOrThrow()

                contexts.activate(activeContext.id).getOrThrow()

                val failure = contexts.activate(brokenContext.id)

                assertTrue(failure.isFailure)
                assertEquals(
                    activeContext.id,
                    store.state.value.activeContextId,
                )
            } finally {
                contexts.close()
                instances.close()
                store.close()
            }
        }

    @Test
    fun liveProviderLossDegradesButKeepsPersistedSelection() =
        runBlocking {
            val store = FakeCompositionStateStore()
            val routes = MutableStateFlow(
                listOf(alphabeticalSortRoute()),
            )
            val instances = InstanceStore(store)
            val contexts = ContextManager(store, routes)

            try {
                val instance = instances.create(
                    name = "Sort",
                    providerId = PROVIDER_ID,
                ).getOrThrow()
                val context = contexts.create(
                    name = "Personal",
                    bindings = listOf(binding(instance.id)),
                ).getOrThrow()

                contexts.activate(context.id).getOrThrow()

                routes.value = emptyList()

                val degraded = ProviderSelectionResolver.resolve(
                    context = context,
                    instances = store.state.value.instances,
                    routes = emptyList(),
                )

                assertTrue(!degraded.isReady)
                assertEquals(
                    ContextResolutionProblemKind.PROVIDER_UNAVAILABLE,
                    degraded.problems.single().kind,
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
    fun referencedInstanceCannotBeRemoved() = runBlocking {
        val store = FakeCompositionStateStore()
        val routes = MutableStateFlow(
            listOf(alphabeticalSortRoute()),
        )
        val instances = InstanceStore(store)
        val contexts = ContextManager(store, routes)

        try {
            val instance = instances.create(
                name = "Sort",
                providerId = PROVIDER_ID,
            ).getOrThrow()

            contexts.create(
                name = "Personal",
                bindings = listOf(binding(instance.id)),
            ).getOrThrow()

            val removal = instances.remove(instance.id)

            assertTrue(removal.isFailure)
            assertEquals(instance, instances.find(instance.id))
        } finally {
            contexts.close()
            instances.close()
            store.close()
        }
    }

    @Test
    fun duplicateCapabilityBindingsAreRejected() = runBlocking {
        val store = FakeCompositionStateStore()
        val routes = MutableStateFlow(
            listOf(alphabeticalSortRoute()),
        )
        val instances = InstanceStore(store)
        val contexts = ContextManager(store, routes)

        try {
            val one = instances.create(
                name = "One",
                providerId = PROVIDER_ID,
            ).getOrThrow()
            val two = instances.create(
                name = "Two",
                providerId = PROVIDER_ID,
            ).getOrThrow()

            val result = contexts.create(
                name = "Invalid",
                bindings = listOf(
                    binding(one.id),
                    binding(two.id),
                ),
            )

            assertTrue(result.isFailure)
            assertTrue(store.state.value.contexts.isEmpty())
            assertNull(store.state.value.activeContextId)
        } finally {
            contexts.close()
            instances.close()
            store.close()
        }
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
