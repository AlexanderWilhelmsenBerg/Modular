package io.github.alexanderwilhelmsenberg.modular.composition

import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleDescriptorCodec
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSettingsControllerTest {
    @Test
    fun defaultSaveCreatesInstanceAndActivatesHomeOrder() = runBlocking {
        val store = FakeStore()
        val controller = controller(store)

        controller.save(PROVIDER_A, "Most Used", "{}", true).getOrThrow()

        val state = store.state.value
        assertEquals(1, state.instances.size)
        assertEquals("Most Used", state.instances.single().name)
        assertEquals("{\"days\":7}", state.instances.single().configurationJson)
        assertEquals(state.contexts.single().id, state.activeContextId)
        assertEquals(
            state.instances.single().id,
            state.contexts.single().bindings.single().instanceId,
        )
        assertTrue(controller.isUsedForAppOrder(PROVIDER_A))
        assertEquals("{\"days\":7}", controller.configurationFor(PROVIDER_A))
    }

    @Test
    fun activeConfigurationCanChangeInOneAtomicUpdate() = runBlocking {
        val store = FakeStore()
        val controller = controller(store)
        controller.save(PROVIDER_A, "Most Used", "{}", true).getOrThrow()
        val original = store.state.value

        controller.save(PROVIDER_A, "Most Used", "{\"days\":30}", true).getOrThrow()

        val updated = store.state.value
        assertEquals(original.activeContextId, updated.activeContextId)
        assertEquals(original.contexts, updated.contexts)
        assertEquals(original.instances.single().id, updated.instances.single().id)
        assertEquals("{\"days\":30}", updated.instances.single().configurationJson)
        assertEquals(2, store.updateCount)
    }

    @Test
    fun switchingProvidersPreservesTheirSeparateSettings() = runBlocking {
        val store = FakeStore()
        val controller = controller(store)
        controller.save(PROVIDER_A, "Most Used", "{\"days\":30}", true).getOrThrow()
        val firstId = store.state.value.instances.single().id

        controller.save(PROVIDER_B, "Reverse", "{\"days\":2}", true).getOrThrow()
        assertFalse(controller.isUsedForAppOrder(PROVIDER_A))
        assertTrue(controller.isUsedForAppOrder(PROVIDER_B))
        assertEquals("{\"days\":30}", controller.configurationFor(PROVIDER_A))
        assertEquals(2, store.state.value.instances.size)

        controller.save(PROVIDER_A, "Most Used", controller.configurationFor(PROVIDER_A), true).getOrThrow()
        assertTrue(controller.isUsedForAppOrder(PROVIDER_A))
        assertEquals(firstId, store.state.value.contexts.single().bindings.single().instanceId)
    }

    @Test
    fun savingInactiveProviderDoesNotChangeActiveSortBinding() = runBlocking {
        val store = FakeStore()
        val controller = controller(store)
        controller.save(PROVIDER_B, "Reverse", "{}", true).getOrThrow()
        val activeBefore = store.state.value.activeContext()
        val activeId = store.state.value.activeContextId

        controller.save(PROVIDER_A, "Most Used", "{\"days\":21}", false).getOrThrow()

        assertEquals(activeBefore, store.state.value.activeContext())
        assertEquals(activeId, store.state.value.activeContextId)
        assertTrue(controller.isUsedForAppOrder(PROVIDER_B))
        assertFalse(controller.isUsedForAppOrder(PROVIDER_A))
        assertEquals("{\"days\":21}", controller.configurationFor(PROVIDER_A))
    }

    @Test
    fun existingActiveInstanceSeedsSettingsWithoutChangingAdvancedState() = runBlocking {
        val store = FakeStore()
        val advancedInstance = ModuleInstance(
            id = "advanced-instance",
            name = "Custom Most Used",
            providerId = PROVIDER_A,
            configurationJson = "{\"days\":11}",
        )
        val advanced = LauncherContext(
            id = "advanced",
            name = "Advanced",
            bindings = listOf(ContextCapabilityBinding(StandardCapabilities.AppsSort, advancedInstance.id)),
        )
        store.state.value = CompositionState(
            instances = listOf(advancedInstance),
            contexts = listOf(advanced),
            activeContextId = advanced.id,
        )
        val controller = controller(store)
        assertEquals("{\"days\":11}", controller.configurationFor(PROVIDER_A))

        controller.save(PROVIDER_A, "Most Used", "{\"days\":12}", true).getOrThrow()

        assertEquals(advancedInstance, store.state.value.instances.first { it.id == advancedInstance.id })
        assertEquals(advanced, store.state.value.contexts.first { it.id == advanced.id })
        assertEquals("{\"days\":12}", controller.configurationFor(PROVIDER_A))
        assertEquals(2, store.state.value.instances.size)
    }

    @Test
    fun validationAndRouteFailuresLeaveWholeStateUntouched() = runBlocking {
        val store = FakeStore()
        val routes = MutableStateFlow(listOf(route(PROVIDER_A), route(PROVIDER_B)))
        val controller = ModuleSettingsController(store, routes)
        controller.save(PROVIDER_A, "Most Used", "{}", true).getOrThrow()
        val before = store.state.value
        val updates = store.updateCount

        assertTrue(controller.save(PROVIDER_A, "Most Used", "{\"days\":-1}", true).isFailure)
        assertEquals(before, store.state.value)
        routes.value = listOf(route(PROVIDER_B))
        assertTrue(controller.save(PROVIDER_A, "Most Used", "{}", true).isFailure)
        assertEquals(before, store.state.value)
        routes.value = listOf(route(PROVIDER_A), route(PROVIDER_A), route(PROVIDER_B))
        assertTrue(controller.save(PROVIDER_A, "Most Used", "{}", true).isFailure)
        assertEquals(before, store.state.value)
        assertEquals(updates, store.updateCount)
    }

    @Test
    fun inactiveSaveAndClearingSortKeepUnrelatedBindingsAndAdvancedContext() = runBlocking {
        val store = FakeStore()
        val otherCapability = CapabilityId("modular.other.capability", 1)
        val otherInstance = ModuleInstance("other-instance", "Other", PROVIDER_B)
        val advanced = LauncherContext(
            id = "advanced",
            name = "Advanced",
            bindings = listOf(ContextCapabilityBinding(otherCapability, otherInstance.id)),
        )
        store.state.value = CompositionState(
            instances = listOf(otherInstance),
            contexts = listOf(advanced),
            activeContextId = advanced.id,
        )
        val routes = MutableStateFlow(listOf(
            route(PROVIDER_A), route(PROVIDER_B),
            route(PROVIDER_B).copy(capability = otherCapability),
        ))
        val controller = ModuleSettingsController(store, routes)

        controller.save(PROVIDER_A, "Most Used", "{\"days\":14}", false).getOrThrow()
        assertEquals(advanced.id, store.state.value.activeContextId)
        assertEquals(listOf(advanced), store.state.value.contexts)

        controller.save(PROVIDER_A, "Most Used", controller.configurationFor(PROVIDER_A), true).getOrThrow()
        assertEquals(advanced, store.state.value.contexts.first { it.id == advanced.id })
        assertEquals(2, store.state.value.activeContext()!!.bindings.size)

        controller.save(PROVIDER_A, "Most Used", controller.configurationFor(PROVIDER_A), false).getOrThrow()
        assertFalse(controller.isUsedForAppOrder(PROVIDER_A))
        assertEquals(listOf(ContextCapabilityBinding(otherCapability, otherInstance.id)),
            store.state.value.activeContext()!!.bindings)
        assertEquals(advanced, store.state.value.contexts.first { it.id == advanced.id })
        assertEquals("{\"days\":14}", controller.configurationFor(PROVIDER_A))
    }

    @Test
    fun dualCapabilityProviderUsesFeaturedAndLeavesOtherBindingsIntact() = runBlocking {
        val store = FakeStore()
        val other = CapabilityId("modular.other.capability", 1)
        val otherInstance = ModuleInstance("other", "Other", PROVIDER_B)
        store.state.value = CompositionState(
            instances = listOf(otherInstance),
            contexts = listOf(LauncherContext("advanced", "Advanced", listOf(
                ContextCapabilityBinding(other, otherInstance.id),
            ))),
            activeContextId = "advanced",
        )
        val controller = ModuleSettingsController(store, MutableStateFlow(listOf(
            featuredRoute(PROVIDER_A).copy(capability = StandardCapabilities.AppsSort),
            featuredRoute(PROVIDER_A), route(PROVIDER_B).copy(capability = other),
        )))

        controller.save(PROVIDER_A, "Most Used", "{\"days\":12}", true).getOrThrow()

        val active = store.state.value.activeContext()!!
        assertEquals(1, active.bindings.count { it.capability == StandardCapabilities.AppsFeatured })
        assertEquals(0, active.bindings.count { it.capability == StandardCapabilities.AppsSort })
        assertTrue(active.bindings.any { it.capability == other })
        assertEquals("Advanced", store.state.value.contexts.first { it.id == "advanced" }.name)
        assertTrue(controller.isUsedForAppOrder(PROVIDER_A))
    }

    @Test
    fun migrationKeepsSelectedInstanceAndAdvancedContext() = runBlocking {
        val store = FakeStore()
        val selected = ModuleInstance("custom-instance", "Custom", PROVIDER_A, "{\"days\":21}")
        val otherCapability = CapabilityId("modular.other.capability", 1)
        val otherInstance = ModuleInstance("other-instance", "Other", PROVIDER_B)
        val original = LauncherContext("custom-context", "Custom", listOf(
            ContextCapabilityBinding(StandardCapabilities.AppsSort, selected.id),
            ContextCapabilityBinding(otherCapability, otherInstance.id),
        ))
        store.state.value = CompositionState(listOf(selected, otherInstance), listOf(original), original.id)
        val routes = MutableStateFlow(listOf(
            featuredRoute(PROVIDER_A).copy(capability = StandardCapabilities.AppsSort),
            featuredRoute(PROVIDER_A),
            route(PROVIDER_B).copy(capability = otherCapability),
        ))
        val controller = ModuleSettingsController(store, routes)

        assertTrue(controller.migrateActiveLegacySortToFeatured().getOrThrow())
        assertEquals(selected, store.state.value.instances.first { it.id == selected.id })
        assertEquals(original, store.state.value.contexts.first { it.id == original.id })
        assertEquals("module-settings:home:v1", store.state.value.activeContextId)
        assertTrue(store.state.value.activeContext()!!.bindings.contains(
            ContextCapabilityBinding(StandardCapabilities.AppsFeatured, selected.id)))
        assertTrue(store.state.value.activeContext()!!.bindings.contains(
            ContextCapabilityBinding(otherCapability, otherInstance.id)))
        assertFalse(controller.migrateActiveLegacySortToFeatured().getOrThrow())

        controller.save(PROVIDER_A, "Most Used", "{\"days\":22}", true).getOrThrow()
        assertEquals(2, store.state.value.instances.size)
        assertEquals(selected.id, store.state.value.activeContext()!!.bindings.first {
            it.capability == StandardCapabilities.AppsFeatured
        }.instanceId)
        assertEquals("{\"days\":22}", store.state.value.instances.first { it.id == selected.id }.configurationJson)
        assertEquals(original, store.state.value.contexts.first { it.id == original.id })
    }

    @Test
    fun migrationWaitsForCompatibleFeaturedRoute() = runBlocking {
        val store = FakeStore()
        val selected = ModuleInstance("custom-instance", "Custom", PROVIDER_A, "{\"days\":21}")
        val original = LauncherContext("custom-context", "Custom", listOf(
            ContextCapabilityBinding(StandardCapabilities.AppsSort, selected.id),
        ))
        store.state.value = CompositionState(listOf(selected), listOf(original), original.id)
        val controller = ModuleSettingsController(store, MutableStateFlow(listOf(route(PROVIDER_A))))

        assertFalse(controller.migrateActiveLegacySortToFeatured().getOrThrow())
        assertEquals(original, store.state.value.activeContext())
    }

    private fun controller(store: FakeStore) = ModuleSettingsController(
        store, MutableStateFlow(listOf(route(PROVIDER_A), route(PROVIDER_B))),
    )

    private fun route(providerId: String): CapabilityRoute = CapabilityRoute(
        capability = StandardCapabilities.AppsSort,
        providerKind = CapabilityProviderKind.MODULE,
        providerId = providerId,
        descriptor = ModuleDescriptorCodec.parse("""
            {"schemaVersion":1,"moduleId":"$providerId","moduleVersion":"1",
             "displayName":"Sort","description":"Sorting provider",
             "coreProtocol":{"min":1,"max":4},
             "providedCapabilities":["modular.apps.sort/v1"],
             "requestedCoreCapabilities":[],
             "instanceConfiguration":{"schemaVersion":1,"fields":[
               {"key":"days","title":"Days","type":"integer","default":7,"min":1,"max":365}
             ]}}
        """.trimIndent()),
    )

    private fun featuredRoute(providerId: String): CapabilityRoute = route(providerId).copy(
        capability = StandardCapabilities.AppsFeatured,
        descriptor = ModuleDescriptorCodec.parse("""
            {"schemaVersion":1,"moduleId":"$providerId","moduleVersion":"1",
             "displayName":"Featured","description":"Featured provider",
             "coreProtocol":{"min":1,"max":4},
             "providedCapabilities":["modular.apps.sort/v1","modular.apps.featured/v1"],
             "requestedCoreCapabilities":[],
             "instanceConfiguration":{"schemaVersion":1,"fields":[
               {"key":"days","title":"Days","type":"integer","default":7,"min":1,"max":365}
             ]}}
        """.trimIndent()),
    )

    private fun CompositionState.activeContext() = contexts.firstOrNull { it.id == activeContextId }

    private class FakeStore : CompositionStateStore {
        override val state = MutableStateFlow(CompositionState())
        override val loadError: StateFlow<String?> = MutableStateFlow(null)
        var updateCount = 0
        override suspend fun update(transform: (CompositionState) -> CompositionState): CompositionState =
            transform(state.value).also {
                updateCount++
                state.value = it
            }
        override fun close() = Unit
    }

    companion object {
        private const val PROVIDER_A = "example.most_used"
        private const val PROVIDER_B = "example.reverse"
    }
}
