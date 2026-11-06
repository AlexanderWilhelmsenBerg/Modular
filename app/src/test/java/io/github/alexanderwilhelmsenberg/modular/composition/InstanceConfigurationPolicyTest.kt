package io.github.alexanderwilhelmsenberg.modular.composition

import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleDescriptorCodec
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceConfigurationPolicyTest {
    @Test
    fun independentInstancesNormalizeDefaultsAndKeepSeparateValues() = runBlocking {
        val store = FakeStore()
        val routes = MutableStateFlow(listOf(route()))
        val instances = InstanceStore(store, routes)
        try {
            val personal = instances.create("Personal", PROVIDER, """{"label":"Personal"}""").getOrThrow()
            val work = instances.create("Work", PROVIDER, """{"label":"Work"}""").getOrThrow()
            assertEquals("""{"enabled":true,"label":"Personal"}""", personal.configurationJson)
            assertEquals("""{"enabled":true,"label":"Work"}""", work.configurationJson)
            assertEquals(2, store.state.value.instances.size)
        } finally {
            instances.close()
        }
    }

    @Test
    fun invalidAndOversizedConfigurationCannotReplaceSavedInstance() = runBlocking {
        val store = FakeStore()
        val routes = MutableStateFlow(listOf(route()))
        val instances = InstanceStore(store, routes)
        try {
            val original = instances.create("Personal", PROVIDER).getOrThrow()
            val unknown = instances.upsert(original.copy(configurationJson = """{"unknown":"private-value"}"""))
            assertTrue(unknown.isFailure)
            assertFalse(unknown.exceptionOrNull()?.message.orEmpty().contains("private-value"))
            assertEquals(original, instances.find(original.id))

            val oversized = instances.upsert(original.copy(configurationJson =
                """{"label":"${"a".repeat(8_193)}"}"""))
            assertTrue(oversized.isFailure)
            assertEquals(original, instances.find(original.id))
        } finally {
            instances.close()
        }
    }

    @Test
    fun activeInstanceEditIsRejectedAndSchemaChangeDegradesSelection() = runBlocking {
        val store = FakeStore()
        val routes = MutableStateFlow(listOf(route()))
        val instances = InstanceStore(store, routes)
        val contexts = ContextManager(store, routes)
        try {
            val instance = instances.create("Personal", PROVIDER, """{"label":"Personal"}""").getOrThrow()
            val context = contexts.create("Personal", listOf(
                ContextCapabilityBinding(StandardCapabilities.AppsSort, instance.id),
            )).getOrThrow()
            contexts.activate(context.id).getOrThrow()

            val failedEdit = instances.upsert(instance.copy(configurationJson = """{"label":"Work"}"""))
            assertTrue(failedEdit.isFailure)
            assertEquals(instance, instances.find(instance.id))
            assertEquals(context.id, store.state.value.activeContextId)

            routes.value = listOf(route(requiredIntegerDescriptor()))
            val degraded = ProviderSelectionResolver.resolve(
                context, store.state.value.instances, routes.value,
            )
            assertFalse(degraded.isReady)
            assertEquals(ContextResolutionProblemKind.CONFIGURATION_INVALID, degraded.problems.single().kind)
            assertEquals(context.id, store.state.value.activeContextId)
        } finally {
            contexts.close()
            instances.close()
        }
    }

    @Test
    fun failedActivationPreservesPriorActiveContextAndMissingProviderConfiguration() = runBlocking {
        val store = FakeStore()
        val routes = MutableStateFlow(listOf(route()))
        val instances = InstanceStore(store, routes)
        val contexts = ContextManager(store, routes)
        try {
            val live = instances.create("Live", PROVIDER).getOrThrow()
            val absent = instances.create("Absent", "absent.provider", """{"future":42}""").getOrThrow()
            assertEquals("""{"future":42}""", absent.configurationJson)
            val active = contexts.create("Active", listOf(
                ContextCapabilityBinding(StandardCapabilities.AppsSort, live.id),
            )).getOrThrow()
            val invalid = contexts.create("Invalid", listOf(
                ContextCapabilityBinding(StandardCapabilities.AppsSort, absent.id),
            )).getOrThrow()
            contexts.activate(active.id).getOrThrow()
            routes.value = routes.value + route().copy(providerId = "absent.provider")
            assertTrue(contexts.activate(invalid.id).isFailure)
            assertEquals(
                ContextResolutionProblemKind.CONFIGURATION_INVALID,
                ProviderSelectionResolver.resolve(invalid, store.state.value.instances, routes.value)
                    .problems.single().kind,
            )
            assertEquals(active.id, store.state.value.activeContextId)
            assertEquals(absent, instances.find(absent.id))
        } finally {
            contexts.close()
            instances.close()
        }
    }

    @Test
    fun legacyProviderAcceptsOnlyEmptyConfiguration() = runBlocking {
        val store = FakeStore()
        val routes = MutableStateFlow(listOf(route(descriptor = null)))
        val instances = InstanceStore(store, routes)
        try {
            assertTrue(instances.create("Empty", PROVIDER, " { } ").isSuccess)
            assertTrue(instances.create("Nonempty", PROVIDER, """{"label":"private"}""").isFailure)
            assertEquals(1, store.state.value.instances.size)
            assertEquals("{}", store.state.value.instances.single().configurationJson)
        } finally {
            instances.close()
        }
    }

    private fun route(descriptor: io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleDescriptor? = standardDescriptor()) =
        CapabilityRoute(
            capability = StandardCapabilities.AppsSort,
            providerKind = CapabilityProviderKind.MODULE,
            providerId = PROVIDER,
            descriptor = descriptor,
        )

    private fun standardDescriptor() = ModuleDescriptorCodec.parse("""
        {"schemaVersion":1,"moduleId":"$PROVIDER","moduleVersion":"1",
         "displayName":"Sort","description":"Sorting provider",
         "coreProtocol":{"min":1,"max":4},
         "providedCapabilities":["modular.apps.sort/v1"],
         "requestedCoreCapabilities":[],
         "instanceConfiguration":{"schemaVersion":1,"fields":[
           {"key":"enabled","title":"Enabled","type":"boolean","default":true},
           {"key":"label","title":"Label","type":"string","default":"Default"}
         ]}}
    """.trimIndent())

    private fun requiredIntegerDescriptor() = ModuleDescriptorCodec.parse("""
        {"schemaVersion":1,"moduleId":"$PROVIDER","moduleVersion":"2",
         "displayName":"Sort","description":"Sorting provider",
         "coreProtocol":{"min":1,"max":4},
         "providedCapabilities":["modular.apps.sort/v1"],
         "requestedCoreCapabilities":[],
         "instanceConfiguration":{"schemaVersion":1,"fields":[
           {"key":"count","title":"Count","type":"integer","default":0}
         ]}}
    """.trimIndent())

    private class FakeStore : CompositionStateStore {
        override val state = MutableStateFlow(CompositionState())
        override val loadError: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun update(transform: (CompositionState) -> CompositionState): CompositionState =
            transform(state.value).also { state.value = it }
        override fun close() = Unit
    }

    companion object {
        private const val PROVIDER = "sample.provider"
    }
}
