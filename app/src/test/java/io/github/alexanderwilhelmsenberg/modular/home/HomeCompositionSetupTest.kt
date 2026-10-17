package io.github.alexanderwilhelmsenberg.modular.home

import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.composition.CompositionState
import io.github.alexanderwilhelmsenberg.modular.composition.CompositionStateStore
import io.github.alexanderwilhelmsenberg.modular.composition.LauncherContext
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeCompositionSetupTest {
    private val route = CapabilityRoute(
        capability = StandardCapabilities.SurfaceHome,
        providerKind = CapabilityProviderKind.MODULE,
        providerId = "example.home",
    )

    @Test fun createsBindsAndActivatesInOneWrite() = runBlocking {
        val store = FakeStore()
        val result = HomeCompositionSetup(store, MutableStateFlow(listOf(route)))
            .createAndActivate(" example.home ", " Personal ").getOrThrow()
        assertEquals(1, store.writes)
        val state = store.state.value
        val instance = state.instances.single()
        val context = state.contexts.single()
        assertEquals("Personal Home", instance.name)
        assertEquals("example.home", instance.providerId)
        assertEquals("Personal", context.name)
        assertEquals(instance.id, context.bindings.single().instanceId)
        assertEquals(StandardCapabilities.SurfaceHome, context.bindings.single().capability)
        assertEquals(context.id, state.activeContextId)
        assertTrue(result.isReady)
        assertEquals(context, result.context)
    }

    @Test fun unavailableOrAmbiguousProviderCannotMutate() = runBlocking {
        for (routes in listOf(emptyList(), listOf(route, route))) {
            val store = FakeStore(original)
            val result = HomeCompositionSetup(store, MutableStateFlow(routes))
                .createAndActivate(route.providerId, "Replacement")
            assertTrue(result.isFailure)
            assertEquals(original, store.state.value)
        }
    }

    @Test fun providerDisappearingWhileWaitingForTransactionPreservesOriginal() = runBlocking {
        val routes = MutableStateFlow(listOf(route))
        val store = FakeStore(original, before = { routes.value = emptyList() })
        assertTrue(HomeCompositionSetup(store, routes)
            .createAndActivate(route.providerId, "Replacement").isFailure)
        assertEquals(original, store.state.value)
    }

    @Test fun persistenceFailureAfterTransformPublishesNothing() = runBlocking {
        val store = FakeStore(original, afterTransform = { error("disk failure") })
        assertTrue(HomeCompositionSetup(store, MutableStateFlow(listOf(route)))
            .createAndActivate(route.providerId, "Replacement").isFailure)
        assertEquals(original, store.state.value)
    }

    @Test fun cancellationBeforeCommitPublishesNothingAndPropagates() = runBlocking {
        val store = FakeStore(original, afterTransform = { throw CancellationException("destroyed") })
        expectCancellation(store)
        assertEquals(original, store.state.value)
    }

    @Test fun cancellationAtCommitReturnLeavesCompleteComposition() = runBlocking {
        val store = FakeStore(original, afterCommit = { throw CancellationException("destroyed") })
        expectCancellation(store)
        val state = store.state.value
        val added = state.contexts.single { it.id != "existing" }
        assertEquals(added.id, state.activeContextId)
        assertEquals(1, added.bindings.size)
        assertEquals(state.instances.single().id, added.bindings.single().instanceId)
        assertEquals(1, store.writes)
        assertTrue(state.contexts.contains(original.contexts.single()))
    }

    @Test fun blankInputDoesNotWrite() = runBlocking {
        val store = FakeStore(original)
        val setup = HomeCompositionSetup(store, MutableStateFlow(listOf(route)))
        assertTrue(setup.createAndActivate("", "Home").isFailure)
        assertTrue(setup.createAndActivate(route.providerId, " ").isFailure)
        assertEquals(0, store.writes)
        assertEquals(original, store.state.value)
    }

    @Test fun separateSetupsCreateDistinctInstances() = runBlocking {
        val store = FakeStore()
        val setup = HomeCompositionSetup(store, MutableStateFlow(listOf(route)))
        val personal = setup.createAndActivate(route.providerId, "Personal").getOrThrow()
        val work = setup.createAndActivate(route.providerId, "Work").getOrThrow()
        assertNotEquals(personal.context!!.id, work.context!!.id)
        assertEquals(2, store.state.value.instances.map { it.id }.toSet().size)
        assertEquals(work.context!!.id, store.state.value.activeContextId)
    }

    private suspend fun expectCancellation(store: FakeStore) {
        try {
            HomeCompositionSetup(store, MutableStateFlow(listOf(route)))
                .createAndActivate(route.providerId, "Replacement")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // The store models the two permitted outcomes at the commit boundary.
        }
    }

    private val original = CompositionState(
        contexts = listOf(LauncherContext("existing", "Existing")),
        activeContextId = "existing",
    )

    private class FakeStore(
        initial: CompositionState = CompositionState(),
        private val before: () -> Unit = {},
        private val afterTransform: () -> Unit = {},
        private val afterCommit: () -> Unit = {},
    ) : CompositionStateStore {
        override val state = MutableStateFlow(initial)
        override val loadError: StateFlow<String?> = MutableStateFlow(null)
        var writes = 0
        override suspend fun update(transform: (CompositionState) -> CompositionState): CompositionState {
            before()
            val next = transform(state.value)
            afterTransform()
            state.value = next
            writes++
            afterCommit()
            return next
        }
        override fun close() = Unit
    }
}
