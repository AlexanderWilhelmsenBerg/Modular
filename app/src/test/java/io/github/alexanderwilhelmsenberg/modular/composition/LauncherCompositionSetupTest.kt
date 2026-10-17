package io.github.alexanderwilhelmsenberg.modular.composition

import io.github.alexanderwilhelmsenberg.modular.capabilities.*
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LauncherCompositionSetupTest {
    private val requests = listOf(
        CompositionProvider(StandardCapabilities.SurfaceHome, "home"),
        CompositionProvider(StandardCapabilities.SurfaceDrawer, "drawer", "{\"profileSerial\":10}"),
        CompositionProvider(StandardCapabilities.AppsSort, "sort"),
    )
    private val routes = MutableStateFlow(requests.map {
        CapabilityRoute(it.capability, CapabilityProviderKind.MODULE, it.providerId)
    })

    @Test fun completeCompositionCommitsOnceAndInstancesAreIndependent() = runBlocking {
        val store = Store()
        val setup = LauncherCompositionSetup(store, routes)
        val personal = setup.createAndActivate("Personal", requests).getOrThrow()
        val work = setup.createAndActivate("Work", requests).getOrThrow()
        assertTrue(personal.isReady)
        assertTrue(work.isReady)
        assertEquals(2, store.writes)
        assertEquals(6, store.state.value.instances.size)
        assertEquals(6, store.state.value.instances.map { it.id }.toSet().size)
        assertEquals(work.context!!.id, store.state.value.activeContextId)
        assertEquals("{\"profileSerial\":10}", work.resolvedBindings.single {
            it.capability == StandardCapabilities.SurfaceDrawer
        }.instance.configurationJson)
    }

    @Test fun invalidOrUnavailableProviderCannotPublishPartialComposition() = runBlocking {
        val store = Store()
        val setup = LauncherCompositionSetup(store, routes)
        assertTrue(setup.createAndActivate("Bad", requests + requests.first()).isFailure)
        assertTrue(setup.createAndActivate("Bad", requests.map {
            if (it.providerId == "drawer") it.copy(configurationJson = "[]") else it
        }).isFailure)
        routes.value = emptyList()
        assertTrue(setup.createAndActivate("Missing", requests).isFailure)
        assertEquals(0, store.writes)
        assertEquals(CompositionState(), store.state.value)
    }

    @Test fun deletingActiveContextRemovesOnlyItsUnusedInstances() = runBlocking {
        val store = Store()
        val setup = LauncherCompositionSetup(store, routes)
        val personal = setup.createAndActivate("Personal", requests).getOrThrow()
        val work = setup.createAndActivate("Work", requests).getOrThrow()
        setup.removeContext(work.context!!.id).getOrThrow()
        assertNull(store.state.value.activeContextId)
        assertEquals(listOf(personal.context), store.state.value.contexts)
        assertEquals(personal.resolvedBindings.map { it.instance.id }.toSet(),
            store.state.value.instances.map { it.id }.toSet())
    }

    @Test fun removingOneBindingIsAtomicAndPreservesOtherContextsAndSharedInstances() = runBlocking<Unit> {
        val store = Store()
        val setup = LauncherCompositionSetup(store, routes)
        val personal = setup.createAndActivate("Personal", requests).getOrThrow().context!!
        val work = setup.createAndActivate("Work", requests).getOrThrow().context!!
        val shared = work.copy(id = "shared", name = "Shared drawer",
            bindings = work.bindings.filter { it.capability == StandardCapabilities.SurfaceDrawer })
        store.state.value = store.state.value.copy(contexts = store.state.value.contexts + shared)
        val before = store.state.value
        val writes = store.writes
        setup.removeBinding(work.id, StandardCapabilities.SurfaceDrawer).getOrThrow()
        assertEquals(writes + 1, store.writes)
        assertNull(store.state.value.activeContextId)
        assertEquals(before.instances, store.state.value.instances)
        assertEquals(personal, store.state.value.contexts.single { it.id == personal.id })
        assertEquals(shared, store.state.value.contexts.single { it.id == shared.id })
        assertFalse(store.state.value.contexts.single { it.id == work.id }.bindings.any {
            it.capability == StandardCapabilities.SurfaceDrawer
        })
        store.state.value = store.state.value.copy(activeContextId = personal.id)
        setup.removeBinding(shared.id, StandardCapabilities.SurfaceDrawer).getOrThrow()
        assertEquals(personal.id, store.state.value.activeContextId)
        val removedId = shared.bindings.single().instanceId
        assertFalse(store.state.value.instances.any { it.id == removedId })
        val after = store.state.value
        val afterWrites = store.writes
        assertTrue(setup.removeBinding("missing", StandardCapabilities.SurfaceHome).isFailure)
        assertTrue(setup.removeBinding(work.id, StandardCapabilities.SurfaceDrawer).isFailure)
        assertEquals(after, store.state.value)
        assertEquals(afterWrites, store.writes)
    }

    private class Store : CompositionStateStore {
        override val state = MutableStateFlow(CompositionState())
        override val loadError = MutableStateFlow<String?>(null)
        var writes = 0
        override suspend fun update(transform: (CompositionState) -> CompositionState): CompositionState {
            val next = transform(state.value)
            state.value = next
            writes++
            return next
        }
        override fun close() = Unit
    }
}
