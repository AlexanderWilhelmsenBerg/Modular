package io.github.alexanderwilhelmsenberg.modular.home

import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.composition.CompositionState
import io.github.alexanderwilhelmsenberg.modular.composition.CompositionStateStore
import io.github.alexanderwilhelmsenberg.modular.composition.ContextManager
import io.github.alexanderwilhelmsenberg.modular.composition.InstanceStore
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCompositionSetupTest {
    @Test
    fun createsBindsAndActivatesAvailableHomeProvider() =
        runBlocking {
            val store = FakeCompositionStateStore()
            val routes = MutableStateFlow(
                listOf(homeRoute(PROVIDER_ID)),
            )
            val instances = InstanceStore(store)
            val contexts = ContextManager(store, routes)
            val setup = HomeCompositionSetup(
                instances = instances,
                contexts = contexts,
                routes = routes,
            )

            try {
                val active = setup.createAndActivate(
                    providerId = PROVIDER_ID,
                    name = "Personal",
                ).getOrThrow()

                val state = store.state.value
                val instance = state.instances.single()
                val context = state.contexts.single()

                assertEquals(PROVIDER_ID, instance.providerId)
                assertEquals("Personal Home", instance.name)
                assertEquals("Personal", context.name)
                assertEquals(context.id, state.activeContextId)
                assertEquals(
                    StandardCapabilities.SurfaceHome,
                    context.bindings.single().capability,
                )
                assertEquals(
                    instance.id,
                    context.bindings.single().instanceId,
                )
                assertEquals(context.id, active.context?.id)
                assertTrue(active.isReady)
            } finally {
                contexts.close()
                instances.close()
                store.close()
            }
        }

    @Test
    fun unavailableProviderFailsWithoutPersistingPartialSetup() =
        runBlocking {
            val store = FakeCompositionStateStore()
            val routes =
                MutableStateFlow<List<CapabilityRoute>>(
                    emptyList(),
                )
            val instances = InstanceStore(store)
            val contexts = ContextManager(store, routes)
            val setup = HomeCompositionSetup(
                instances = instances,
                contexts = contexts,
                routes = routes,
            )

            try {
                val result = setup.createAndActivate(
                    providerId = PROVIDER_ID,
                    name = "Personal",
                )

                assertTrue(result.isFailure)
                assertTrue(store.state.value.instances.isEmpty())
                assertTrue(store.state.value.contexts.isEmpty())
                assertEquals(
                    null,
                    store.state.value.activeContextId,
                )
            } finally {
                contexts.close()
                instances.close()
                store.close()
            }
        }

    @Test
    fun activationFailureRollsBackInstanceAndContext() =
        runBlocking {
            val routes = MutableStateFlow(
                listOf(homeRoute(PROVIDER_ID)),
            )
            val store = FakeCompositionStateStore {
                previous,
                next,
                ->
                if (
                    previous.contexts.isEmpty() &&
                    next.contexts.isNotEmpty()
                ) {
                    routes.value = emptyList()
                }
            }
            val instances = InstanceStore(store)
            val contexts = ContextManager(store, routes)
            val setup = HomeCompositionSetup(
                instances = instances,
                contexts = contexts,
                routes = routes,
            )

            try {
                val result = setup.createAndActivate(
                    providerId = PROVIDER_ID,
                    name = "Personal",
                )

                assertTrue(result.isFailure)
                assertTrue(store.state.value.instances.isEmpty())
                assertTrue(store.state.value.contexts.isEmpty())
                assertEquals(
                    null,
                    store.state.value.activeContextId,
                )
            } finally {
                contexts.close()
                instances.close()
                store.close()
            }
        }

    @Test
    fun ambiguousProviderFailsWithoutPersistingPartialSetup() =
        runBlocking {
            val store = FakeCompositionStateStore()
            val routes = MutableStateFlow(
                listOf(
                    homeRoute(PROVIDER_ID),
                    homeRoute(PROVIDER_ID),
                ),
            )
            val instances = InstanceStore(store)
            val contexts = ContextManager(store, routes)
            val setup = HomeCompositionSetup(
                instances = instances,
                contexts = contexts,
                routes = routes,
            )

            try {
                val result = setup.createAndActivate(
                    providerId = PROVIDER_ID,
                    name = "Personal",
                )

                assertTrue(result.isFailure)
                assertTrue(store.state.value.instances.isEmpty())
                assertTrue(store.state.value.contexts.isEmpty())
            } finally {
                contexts.close()
                instances.close()
                store.close()
            }
        }

    private fun homeRoute(
        providerId: String,
    ) = CapabilityRoute(
        capability = StandardCapabilities.SurfaceHome,
        providerKind = CapabilityProviderKind.MODULE,
        providerId = providerId,
    )

    private class FakeCompositionStateStore(
        private val afterUpdate:
            ((CompositionState, CompositionState) -> Unit)? = null,
    ) : CompositionStateStore {
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
            val previous = mutableState.value
            val next = transform(previous)
            mutableState.value = next
            afterUpdate?.invoke(previous, next)
            return next
        }

        override fun close() = Unit
    }

    companion object {
        private const val PROVIDER_ID =
            "modular.reference.home.minimal"
    }

    @Test
    fun failedNewHomePreservesPreviouslyActiveContext() =
        runBlocking {
            val routes = MutableStateFlow(
                listOf(
                    homeRoute(PROVIDER_ID),
                    homeRoute("existing.provider"),
                ),
            )
            var dropNewProvider = false
            val store = FakeCompositionStateStore { previous, next ->
                if (
                    dropNewProvider &&
                    next.contexts.size > previous.contexts.size
                ) {
                    routes.value = listOf(
                        homeRoute("existing.provider"),
                    )
                }
            }
            val instances = InstanceStore(store)
            val contexts = ContextManager(store, routes)
            val setup = HomeCompositionSetup(
                instances = instances,
                contexts = contexts,
                routes = routes,
            )

            try {
                val existing = instances.create(
                    name = "Existing Home",
                    providerId = "existing.provider",
                ).getOrThrow()
                val original = contexts.create(
                    name = "Existing",
                    bindings = listOf(
                        io.github.alexanderwilhelmsenberg.modular.composition.ContextCapabilityBinding(
                            capability = StandardCapabilities.SurfaceHome,
                            instanceId = existing.id,
                        ),
                    ),
                ).getOrThrow()
                contexts.activate(original.id).getOrThrow()

                dropNewProvider = true
                val result = setup.createAndActivate(
                    providerId = PROVIDER_ID,
                    name = "Replacement",
                )

                assertTrue(result.isFailure)
                assertEquals(
                    original.id,
                    store.state.value.activeContextId,
                )
                assertEquals(
                    listOf(original.id),
                    store.state.value.contexts.map { it.id },
                )
                assertEquals(
                    listOf(existing.id),
                    store.state.value.instances.map { it.id },
                )
            } finally {
                contexts.close()
                instances.close()
                store.close()
            }
        }
}
