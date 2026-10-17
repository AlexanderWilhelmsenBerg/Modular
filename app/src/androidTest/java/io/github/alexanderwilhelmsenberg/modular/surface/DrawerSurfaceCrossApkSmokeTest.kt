package io.github.alexanderwilhelmsenberg.modular.surface

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alexanderwilhelmsenberg.modular.apps.*
import io.github.alexanderwilhelmsenberg.modular.capabilities.*
import io.github.alexanderwilhelmsenberg.modular.composition.*
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.ISurfaceDrawerCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceNodeParcel
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRegistry
import io.github.alexanderwilhelmsenberg.modular.security.*
import io.github.alexanderwilhelmsenberg.modular.mcp.*
import kotlinx.serialization.json.*
import java.util.UUID
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawerSurfaceCrossApkSmokeTest {
    @Test fun sortsFiltersLaunchesAndRevokesAcrossSeparateApks() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val moduleInfo = context.packageManager.getApplicationInfo(DRAWER_PACKAGE, 0)
        assertNotEquals(context.applicationInfo.uid, moduleInfo.uid)
        val repository = RecordingRepository()
        val grants = DataStoreModuleGrantStateStore(context, "drawer-grants-" + UUID.randomUUID())
        val security = ModuleSecurityManager(AndroidModuleIdentityResolver(context), grants,
            officialBootstrapCapabilities = OfficialModuleBootstrapPolicy.capabilities)
        val registry = ModuleRegistry(context, CoreHostFactory(context, repository, security), "drawer-policy-" + UUID.randomUUID())
        val router = CapabilityRouter(registry)
        val store = DataStoreCompositionStateStore(context, "drawer-composition-" + UUID.randomUUID())
        val contexts = ContextManager(store, router.routes)
        try {
            registry.refresh()
            withTimeout(20_000) { router.routes.first { routes ->
                listOf(StandardCapabilities.SurfaceHome, StandardCapabilities.SurfaceDrawer, StandardCapabilities.AppsSort)
                    .all { capability -> routes.any { it.capability == capability } }
            } }
            val setup = LauncherCompositionSetup(store, router.routes)
            fun providers(profile: Long) = listOf(
                CompositionProvider(StandardCapabilities.SurfaceHome, "modular.reference.home.minimal"),
                CompositionProvider(StandardCapabilities.SurfaceDrawer, "modular.reference.drawer.basic",
                    "{\"profileSerial\":$profile}"),
                CompositionProvider(StandardCapabilities.AppsSort, "modular.reference.sort.alphabetical"),
            )
            val personal = setup.createAndActivate("Personal", providers(0)).getOrThrow()
            val work = setup.createAndActivate("Work", providers(10)).getOrThrow()
            assertNotEquals(personal.context!!.id, work.context!!.id)
            val client = DrawerSurfaceClient(router, security)
            val document = client.render(personal, repository.catalog.value.apps).getOrThrow()
            val buttons = document.root.children.filter { it.type == SurfaceNodeParcel.TYPE_BUTTON }
            assertEquals(listOf("Alpha", "Zulu"), buttons.map { it.text })
            client.performAction(personal, buttons.first().actionId!!).getOrThrow()
            assertEquals("example.alpha", repository.launched.single().packageName)
            val workDocument = client.render(work, repository.catalog.value.apps).getOrThrow()
            assertEquals(listOf("Work Mail"), workDocument.root.children
                .filter { it.type == SurfaceNodeParcel.TYPE_BUTTON }.map { it.text })
            contexts.activate(personal.context!!.id).getOrThrow()
            contexts.activate(work.context!!.id).getOrThrow()
            withTimeout(5000) { contexts.active.first { it.context?.id == work.context!!.id } }
            val toolRegistry = ModuleToolRegistry(contexts, router, registry, security, context)
            val broker = McpBroker(context, contexts, store, repository, registry, toolRegistry,
                "drawer-mcp-policy-" + UUID.randomUUID())
            try {
                broker.refreshTools()
                val proposal = broker.state.value.tools.single { it.name == "drawer.status" }
                broker.start(0)
                val arguments = buildJsonObject {
                    put("approvalKey", proposal.approvalKey); put("arguments", JsonObject(emptyMap()))
                }
                assertTrue(runCatching { broker.execute("launcher.module.invoke", arguments) }.isFailure)
                broker.setPolicy(BrokerPolicy(approvedModuleTools = setOf(proposal.approvalKey)))
                assertTrue(broker.execute("launcher.module.invoke", arguments).contains("ready"))
                val successfulCall = broker.state.value.audit.last { it.decision == "success" }
                assertEquals(proposal.providerId, successfulCall.targetModule)
                assertEquals(proposal.instanceId, successfulCall.targetInstance)
                assertTrue(broker.state.value.audit.any {
                    it.correlationId == successfulCall.correlationId && it.decision == "attempt"
                })
                broker.setPolicy(BrokerPolicy())
                assertTrue(runCatching { broker.execute("launcher.module.invoke", arguments) }.isFailure)
            } finally { broker.stop() }

            security.revoke(DRAWER_PACKAGE, CoreCapabilities.AppsLaunch).getOrThrow()
            val workAction = workDocument.root.children.single { it.type == SurfaceNodeParcel.TYPE_BUTTON }.actionId!!
            assertTrue(client.performAction(work, workAction).isFailure)
            assertEquals(1, repository.launched.size)

            val drawerRoute = work.routeFor(StandardCapabilities.SurfaceDrawer)!!
            val retained = ISurfaceDrawerCapability.Stub.asInterface(router.acquireModuleBinder(drawerRoute).getOrThrow())
            registry.setEnabled(drawerRoute.component!!, false).getOrThrow()
            assertTrue(client.render(work, repository.catalog.value.apps).isFailure)
            try {
                retained.performAction(work.resolvedBindings.single {
                    it.capability == StandardCapabilities.SurfaceDrawer
                }.instance.id, workAction)
                fail("Disabled module must revoke retained session Binder")
            } catch (_: Exception) { }
            registry.setEnabled(drawerRoute.component!!, true).getOrThrow()
            withTimeout(20_000) { router.routes.first { routes ->
                routes.any { it.capability == StandardCapabilities.SurfaceDrawer }
            } }
            client.render(work, repository.catalog.value.apps).getOrThrow()
            setup.removeBinding(work.context!!.id, StandardCapabilities.SurfaceDrawer).getOrThrow()
            withTimeout(5000) { store.state.first { state ->
                state.activeContextId == null && state.contexts.single { it.id == work.context!!.id }.bindings.none {
                    it.capability == StandardCapabilities.SurfaceDrawer
                }
            } }
            assertNull(store.state.value.activeContextId)
            assertTrue(router.providersFor(StandardCapabilities.SurfaceDrawer).isNotEmpty())
            assertFalse(store.state.value.contexts.single { it.id == work.context!!.id }.bindings.any {
                it.capability == StandardCapabilities.SurfaceDrawer
            })
            val restoredPersonal = contexts.activate(personal.context!!.id).getOrThrow()
            client.render(restoredPersonal, repository.catalog.value.apps).getOrThrow()
        } finally {
            contexts.close()
            store.close()
            router.close()
            registry.close()
            grants.close()
            repository.close()
        }
    }

    private class RecordingRepository : AppRepository {
        override val catalog: StateFlow<AppCatalog> = MutableStateFlow(AppCatalog(
            generation = 1,
            apps = listOf(app("example.zulu", "Zulu", 0), app("example.work", "Work Mail", 10),
                app("example.alpha", "Alpha", 0)),
        ))
        val launched = mutableListOf<AppKey>()
        override fun refresh() = Unit
        override suspend fun launch(key: AppKey): Result<Unit> { launched += key; return Result.success(Unit) }
        override fun close() = Unit
    }

    companion object {
        private const val DRAWER_PACKAGE = "io.github.alexanderwilhelmsenberg.modular.reference.drawer.basic"
        private fun app(packageName: String, label: String, profile: Long) =
            LaunchableApp(AppKey(packageName, "$packageName.Main", profile), label, 0, true, null)
    }
}
