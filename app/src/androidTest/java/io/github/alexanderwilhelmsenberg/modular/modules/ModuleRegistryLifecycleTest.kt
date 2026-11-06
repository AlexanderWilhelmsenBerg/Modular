package io.github.alexanderwilhelmsenberg.modular.modules

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.capabilities.CoreHostFactory
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularHost
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularModuleService
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.security.ModuleGrantState
import io.github.alexanderwilhelmsenberg.modular.security.ModuleGrantStateStore
import io.github.alexanderwilhelmsenberg.modular.security.ModuleIdentity
import io.github.alexanderwilhelmsenberg.modular.security.ModuleIdentityResolver
import io.github.alexanderwilhelmsenberg.modular.security.ModuleSecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ModuleRegistryLifecycleTest {
    private lateinit var baseContext: Context

    @Before
    fun clearPreferences() {
        baseContext = ApplicationProvider.getApplicationContext()
        baseContext.getSharedPreferences("module_management", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun missingServiceCallbackTimesOutAndRefreshDoesNotRetryAutomatically() = runBlocking {
        val context = RegistryContext(baseContext)
        val bindings = CopyOnWriteArrayList<ServiceConnection>()
        val registry = newRegistry(context, timeoutMillis = 80L, bindings = bindings)
        try {
            withContext(Dispatchers.Main) { registry.refresh() }
            assertEquals(1, bindings.size)
            awaitStatus(registry, ModuleConnectionStatus.FAILED)
            withContext(Dispatchers.Main) { registry.refresh() }
            assertEquals(1, bindings.size)

            withContext(Dispatchers.Main) { registry.retry(COMPONENT) }
            assertEquals(2, bindings.size)
        } finally {
            withContext(Dispatchers.Main) { registry.close() }
        }
    }

    @Test
    fun failedHandshakeCanBeRetriedSuccessfully() = runBlocking {
        val context = RegistryContext(baseContext)
        val bindings = CopyOnWriteArrayList<ServiceConnection>()
        val registry = newRegistry(context, timeoutMillis = 2_000L, bindings = bindings)
        val calls = MutableStateFlow(0)
        val service = object : IModularModuleService.Stub() {
            override fun getProtocolVersion(): Int {
                if (calls.value++ == 0) throw SecurityException("denied once")
                return 1
            }
            override fun getModuleId() = "sample.module"
            override fun getModuleVersion() = "1.0"
            override fun getCapabilities(): Array<String> = emptyArray()
            override fun openSession(sessionId: String?, host: IModularHost?) = Unit
            override fun closeSession(sessionId: String?) = Unit
            override fun getCapabilityBinder(sessionId: String?, capabilityId: String?) = null
            override fun getRequestedCoreCapabilities(): Array<String> = emptyArray()
            override fun getDescriptorJson() = ""
            override fun getInstanceCapabilityBinder(sessionId: String?, instanceId: String?, configurationJson: String?, capabilityId: String?) = null
        }

        try {
            withContext(Dispatchers.Main) { registry.refresh() }
            withContext(Dispatchers.Main) { bindings.last().onServiceConnected(COMPONENT, service) }
            awaitStatus(registry, ModuleConnectionStatus.FAILED)
            assertTrue(registry.modules.value.single().error.orEmpty().contains("SecurityException"))

            withContext(Dispatchers.Main) { registry.retry(COMPONENT) }
            withContext(Dispatchers.Main) { bindings.last().onServiceConnected(COMPONENT, service) }
            awaitStatus(registry, ModuleConnectionStatus.CONNECTED)
            assertNotNull(registry.modules.value.single().moduleId)
        } finally {
            withContext(Dispatchers.Main) { registry.close() }
        }
    }

    @Test
    fun blockedHandshakeTimesOutAndDisableIgnoresLateResult() = runBlocking {
        val context = RegistryContext(baseContext)
        val bindings = CopyOnWriteArrayList<ServiceConnection>()
        val registry = newRegistry(context, timeoutMillis = 500L, bindings = bindings)
        val enteredOpenSession = CountDownLatch(1)
        val releaseOpenSession = CountDownLatch(1)
        val closeCalls = MutableStateFlow(0)
        val service = object : IModularModuleService.Stub() {
            override fun getProtocolVersion() = 2
            override fun getModuleId() = "sample.module"
            override fun getModuleVersion() = "1.0"
            override fun getCapabilities(): Array<String> = emptyArray()
            override fun openSession(sessionId: String?, host: IModularHost?) {
                enteredOpenSession.countDown()
                releaseOpenSession.await(5, TimeUnit.SECONDS)
            }
            override fun closeSession(sessionId: String?) { closeCalls.value += 1 }
            override fun getCapabilityBinder(sessionId: String?, capabilityId: String?) = null
            override fun getRequestedCoreCapabilities(): Array<String> = emptyArray()
            override fun getDescriptorJson() = ""
            override fun getInstanceCapabilityBinder(sessionId: String?, instanceId: String?, configurationJson: String?, capabilityId: String?) = null
        }

        try {
            withContext(Dispatchers.Main) { registry.refresh() }
            withContext(Dispatchers.Main) { bindings.last().onServiceConnected(COMPONENT, service) }
            assertTrue(enteredOpenSession.await(2, TimeUnit.SECONDS))
            // Failure must be published while the remote call is still blocked.
            withTimeout(1_500L) { awaitStatus(registry, ModuleConnectionStatus.FAILED) }

            withContext(Dispatchers.Main) { registry.setEnabled(COMPONENT, false) }
            releaseOpenSession.countDown()
            withTimeout(2_000L) { while (closeCalls.value == 0) delay(10) }
            delay(80)
            assertEquals(ModuleConnectionStatus.DISABLED, registry.modules.value.single().status)
        } finally {
            releaseOpenSession.countDown()
            withContext(Dispatchers.Main) { registry.close() }
        }
    }

    @Test
    fun v4WithoutPackagedDescriptorIsVisibleButCannotConnect() = runBlocking {
        val bindings = CopyOnWriteArrayList<ServiceConnection>()
        val registry = newRegistry(RegistryContext(baseContext), 2_000L, bindings)
        val service = object : IModularModuleService.Stub() {
            override fun getProtocolVersion() = 4
            override fun getModuleId() = "sample.module"
            override fun getModuleVersion() = "1.0"
            override fun getCapabilities(): Array<String> = arrayOf("apps.sort/v1")
            override fun getRequestedCoreCapabilities(): Array<String> = emptyArray()
            override fun getDescriptorJson() = "{}"
            override fun openSession(sessionId: String?, host: IModularHost?) = Unit
            override fun closeSession(sessionId: String?) = Unit
            override fun getCapabilityBinder(sessionId: String?, capabilityId: String?) = null
            override fun getInstanceCapabilityBinder(sessionId: String?, instanceId: String?, configurationJson: String?, capabilityId: String?) = null
        }
        try {
            withContext(Dispatchers.Main) { registry.refresh() }
            withContext(Dispatchers.Main) { bindings.single().onServiceConnected(COMPONENT, service) }
            awaitStatus(registry, ModuleConnectionStatus.INCOMPATIBLE)
            assertTrue(registry.modules.value.single().error.orEmpty().contains("packaged module descriptor"))
            assertEquals(null, registry.modules.value.single().sessionId)
        } finally {
            withContext(Dispatchers.Main) { registry.close() }
        }
    }

    @Test
    fun malformedDescriptorMetadataStaysVisibleAndIsNeverBound() = runBlocking {
        val metadata = Bundle().apply { putString(ModuleContract.MODULE_DESCRIPTOR_METADATA_KEY, "not a resource") }
        val bindings = CopyOnWriteArrayList<ServiceConnection>()
        val registry = newRegistry(RegistryContext(baseContext), 2_000L, bindings, metadata)
        try {
            withContext(Dispatchers.Main) { registry.refresh() }
            val record = registry.modules.value.single()
            assertEquals(ModuleConnectionStatus.INCOMPATIBLE, record.status)
            assertTrue(record.error.orEmpty().contains("raw JSON resource"))
            assertTrue(bindings.isEmpty())
        } finally {
            withContext(Dispatchers.Main) { registry.close() }
        }
    }

    @Test
    fun legacyHelperAcceptsEmptyConfigurationAndRejectsNonemptyConfiguration() = runBlocking {
        val bindings = CopyOnWriteArrayList<ServiceConnection>()
        val registry = newRegistry(RegistryContext(baseContext), 2_000L, bindings)
        var binderCalls = 0
        val service = object : IModularModuleService.Stub() {
            override fun getProtocolVersion() = 2
            override fun getModuleId() = "sample.module"
            override fun getModuleVersion() = "1.0"
            override fun getCapabilities(): Array<String> = arrayOf("apps.sort/v1")
            override fun getRequestedCoreCapabilities(): Array<String> = emptyArray()
            override fun getDescriptorJson() = ""
            override fun openSession(sessionId: String?, host: IModularHost?) = Unit
            override fun closeSession(sessionId: String?) = Unit
            override fun getCapabilityBinder(sessionId: String?, capabilityId: String?) = Binder().also { binderCalls++ }
            override fun getInstanceCapabilityBinder(sessionId: String?, instanceId: String?, configurationJson: String?, capabilityId: String?) = null
        }
        try {
            withContext(Dispatchers.Main) { registry.refresh() }
            withContext(Dispatchers.Main) { bindings.single().onServiceConnected(COMPONENT, service) }
            awaitStatus(registry, ModuleConnectionStatus.CONNECTED)
            val sessionId = registry.modules.value.single().sessionId
            assertTrue(registry.acquireCapabilityBinder(COMPONENT, StandardCapabilities.AppsSort,
                "instance", " { } ", sessionId).isSuccess)
            assertTrue(registry.acquireCapabilityBinder(COMPONENT, StandardCapabilities.AppsSort,
                "instance", "{\"setting\":true}", sessionId).isFailure)
            assertEquals(1, binderCalls)
        } finally {
            withContext(Dispatchers.Main) { registry.close() }
        }
    }

    private suspend fun awaitStatus(registry: ModuleRegistry, status: ModuleConnectionStatus) {
        withTimeout(3_000L) {
            while (registry.modules.value.singleOrNull()?.status != status) delay(10)
        }
    }

    private fun newRegistry(
        context: Context,
        timeoutMillis: Long,
        bindings: MutableList<ServiceConnection>,
        metadata: Bundle? = null,
    ): ModuleRegistry = ModuleRegistry(
        context = context,
        hostFactory = createHostFactory(context),
        handshakeTimeoutMillis = timeoutMillis,
        serviceDiscoveryOverride = { listOf(resolveInfo(metadata)) },
        serviceBindingOverride = { _, connection, _ -> bindings += connection; true },
    )

    private fun resolveInfo(metadata: Bundle? = null) = ResolveInfo().apply {
        serviceInfo = ServiceInfo().apply {
            packageName = COMPONENT.packageName
            name = COMPONENT.className
            applicationInfo = ApplicationInfo()
            metaData = metadata
        }
    }

    private fun createHostFactory(context: Context): CoreHostFactory {
        val repository = object : AppRepository {
            override val catalog = MutableStateFlow(AppCatalog())
            override fun refresh() = Unit
            override suspend fun launch(key: AppKey): Result<Unit> = Result.success(Unit)
            override fun close() = Unit
        }
        val grants = object : ModuleGrantStateStore {
            override val state = MutableStateFlow(ModuleGrantState())
            override val loadError = MutableStateFlow<String?>(null)
            override suspend fun update(transform: (ModuleGrantState) -> ModuleGrantState): ModuleGrantState =
                transform(state.value).also { state.value = it }
            override fun close() = Unit
        }
        val identities = object : ModuleIdentityResolver {
            override fun resolve(packageName: String): Result<ModuleIdentity> =
                Result.success(ModuleIdentity(packageName, listOf("0".repeat(64))))
            override fun isSameSignerAsCore(identity: ModuleIdentity) = false
        }
        return CoreHostFactory(
            context,
            repository,
            ModuleSecurityManager(identities, grants, emptySet()),
        )
    }

    private class RegistryContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
    }

    private companion object {
        val COMPONENT = ComponentName("example.module", "example.module.ModuleService")
    }
}
