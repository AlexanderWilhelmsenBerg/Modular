package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.composition.ContextCapabilityBinding
import io.github.alexanderwilhelmsenberg.modular.composition.ContextManager
import io.github.alexanderwilhelmsenberg.modular.composition.DataStoreCompositionStateStore
import io.github.alexanderwilhelmsenberg.modular.composition.InstanceStore
import io.github.alexanderwilhelmsenberg.modular.home.HomeSortController
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppSortCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortRequestParcel
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRegistry
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleConnectionStatus
import io.github.alexanderwilhelmsenberg.modular.security.AndroidModuleIdentityResolver
import io.github.alexanderwilhelmsenberg.modular.security.DataStoreModuleGrantStateStore
import io.github.alexanderwilhelmsenberg.modular.security.ModuleSecurityManager
import io.github.alexanderwilhelmsenberg.modular.security.OfficialModuleBootstrapPolicy
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises an ordinary, independently signed APK through real Binder. */
@RunWith(AndroidJUnit4::class)
class CommunityModuleAcceptanceTest {
    @Test
    fun independentSignerInstancesGrantsAndFallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val moduleInfo = context.packageManager.getApplicationInfo(MODULE_PACKAGE, 0)
        assertNotEquals(context.applicationInfo.uid, moduleInfo.uid)
        assertNotEquals(
            "Community fixture must not have Core's signing identity",
            PackageManager.SIGNATURE_MATCH,
            context.packageManager.checkSignatures(context.packageName, MODULE_PACKAGE),
        )
        val callerProbe = context.contentResolver.call(
            Uri.parse("content://$MODULE_PACKAGE.probe"), "probeUntrustedCaller", null, null,
        )
        assertTrue("Fixture UID was accepted as Core caller", callerProbe?.getBoolean("rejected") == true)

        val repository = FakeAppRepository(
            listOf(app("z.pkg", "Zulu"), app("b.pkg", "beta"), app("a.pkg", "Alpha"), app("0.pkg", "alpha")),
        )
        val grantStore = DataStoreModuleGrantStateStore(context, "community-grants-${UUID.randomUUID()}")
        val security = ModuleSecurityManager(
            AndroidModuleIdentityResolver(context),
            grantStore,
            OfficialModuleBootstrapPolicy.capabilities,
        )
        val registry = ModuleRegistry(context, CoreHostFactory(context, repository, security))
        val router = CapabilityRouter(registry)
        val stateStore = DataStoreCompositionStateStore(context, "community-composition-${UUID.randomUUID()}")
        val instances = InstanceStore(stateStore, router.routes)
        val contexts = ContextManager(stateStore, router.routes)
        val client = AppSortClient(router)
        var controller: HomeSortController? = null
        var disabled = false

        try {
            registry.refresh()
            val route = withTimeout(15_000) {
                router.routes.first { routes -> routes.any { it.providerId == MODULE_ID } }
                    .single { it.providerId == MODULE_ID }
            }
            val component = requireNotNull(route.component)
            val mismatched = withTimeout(15_000) {
                registry.modules.first { modules -> modules.any {
                    it.component.className.endsWith(".MismatchedDescriptorService") &&
                        it.status == ModuleConnectionStatus.INCOMPATIBLE
                } }.single { it.component.className.endsWith(".MismatchedDescriptorService") }
            }
            assertTrue(mismatched.error.orEmpty().contains("descriptor", ignoreCase = true) ||
                mismatched.error.orEmpty().contains("handshake", ignoreCase = true))

            val normal = instances.create("Normal", MODULE_ID, """{"reverse":false}""").getOrThrow()
            val reverse = instances.create("Reverse", MODULE_ID, """{"reverse":true}""").getOrThrow()
            assertNotEquals(normal.id, reverse.id)
            val normalContext = contexts.create(
                "Normal", listOf(ContextCapabilityBinding(StandardCapabilities.AppsSort, normal.id)),
            ).getOrThrow()
            val reverseContext = contexts.create(
                "Reverse", listOf(ContextCapabilityBinding(StandardCapabilities.AppsSort, reverse.id)),
            ).getOrThrow()

            val first = contexts.activate(normalContext.id).getOrThrow()
            val oldBinder = IAppSortCapability.Stub.asInterface(
                router.acquireModuleBinder(route, normal.id, normal.configurationJson).getOrThrow(),
            )
            assertEquals(ASCENDING, sort(client, first, repository.catalog.value.apps))
            assertTrue(oldBinder.sort(sortRequest()).message.orEmpty().contains("catalog=denied"))

            val authorization = security.prepareSession(MODULE_PACKAGE, setOf(CoreCapabilities.AppsCatalog))
            assertFalse(security.isGranted(authorization, CoreCapabilities.AppsCatalog))
            security.grant(authorization, CoreCapabilities.AppsCatalog).getOrThrow()
            assertTrue(oldBinder.sort(sortRequest()).message.orEmpty().contains("catalog=granted"))
            security.revoke(MODULE_PACKAGE, CoreCapabilities.AppsCatalog).getOrThrow()
            assertTrue(oldBinder.sort(sortRequest()).message.orEmpty().contains("catalog=denied"))

            val second = contexts.activate(reverseContext.id).getOrThrow()
            assertEquals(DESCENDING, sort(client, second, repository.catalog.value.apps))
            assertEquals(normal.id, first.resolvedBindings.single().instance.id)
            assertEquals(reverse.id, second.resolvedBindings.single().instance.id)

            // A rejected edit leaves the stored configuration and active choice intact.
            assertTrue(instances.upsert(normal.copy(configurationJson = """{"reverse":"yes"}""")).isFailure)
            assertEquals(first.resolvedBindings.single().instance.configurationJson, instances.find(normal.id)?.configurationJson)
            assertEquals(reverseContext.id, stateStore.state.value.activeContextId)
            assertEquals(DESCENDING, sort(client, second, repository.catalog.value.apps))
            val absent = instances.create("Absent", "modular.test.absent").getOrThrow()
            val unavailable = contexts.create(
                "Unavailable", listOf(ContextCapabilityBinding(StandardCapabilities.AppsSort, absent.id)),
            ).getOrThrow()
            assertTrue(contexts.activate(unavailable.id).isFailure)
            assertEquals(reverseContext.id, stateStore.state.value.activeContextId)

            val edited = instances.upsert(normal.copy(configurationJson = """{"reverse":true}""")).getOrThrow()
            assertEquals("""{"reverse":true}""", edited.configurationJson)
            assertEquals(DESCENDING, sort(client, second, repository.catalog.value.apps))
            val changed = contexts.activate(normalContext.id).getOrThrow()
            assertEquals(DESCENDING, sort(client, changed, repository.catalog.value.apps))
            assertTrue("Old instance endpoint remained active after configuration replacement",
                runCatching { oldBinder.sort(sortRequest()) }.isFailure)

            // Restore opposite behavior, then observe the actual Home controller.
            contexts.activate(reverseContext.id).getOrThrow()
            instances.upsert(normal.copy(configurationJson = """{"reverse":false}""")).getOrThrow()
            assertEquals(ASCENDING, sort(client, contexts.activate(normalContext.id).getOrThrow(), repository.catalog.value.apps))
            assertEquals(DESCENDING, sort(client, contexts.activate(reverseContext.id).getOrThrow(), repository.catalog.value.apps))

            val homeController = HomeSortController(
                catalog = repository.catalog,
                activeContext = contexts.active,
                routes = router.routes,
                sort = { selectedRoute, binding, apps, locale -> client.sort(selectedRoute, binding, apps, locale) },
                localeTag = "en",
                timeoutMillis = 5_000,
            )
            controller = homeController
            withTimeout(15_000) { homeController.orderedApps.first { it.map(LaunchableApp::label) == DESCENDING } }
            val binderAtDisable = IAppSortCapability.Stub.asInterface(
                router.acquireModuleBinder(route, reverse.id, reverse.configurationJson).getOrThrow(),
            )
            assertEquals(0, binderAtDisable.sort(sortRequest()).status)

            registry.setEnabled(component, false)
            disabled = true
            assertTrue("Disabled session left old endpoint callable",
                runCatching { binderAtDisable.sort(sortRequest()) }.isFailure)
            withTimeout(15_000) {
                router.routes.first { routes -> routes.none { it.component == component } }
                homeController.orderedApps.first { it.map(LaunchableApp::label) == CORE_ORDER }
            }
            assertEquals(reverseContext.id, stateStore.state.value.activeContextId)

            registry.setEnabled(component, true)
            disabled = false
            withTimeout(15_000) { router.routes.first { routes -> routes.any { it.component == component } } }
            withTimeout(15_000) { homeController.orderedApps.first { it.map(LaunchableApp::label) == DESCENDING } }

            contexts.deactivate().getOrThrow()
            withTimeout(5_000) { homeController.orderedApps.first { it.map(LaunchableApp::label) == CORE_ORDER } }
        } finally {
            controller?.close()
            if (disabled) registry.setEnabled(
                android.content.ComponentName(MODULE_PACKAGE, "$MODULE_PACKAGE.CommunitySortService"), true,
            )
            client.close()
            contexts.close()
            instances.close()
            stateStore.close()
            router.close()
            registry.close()
            grantStore.close()
            repository.close()
        }
    }

    private suspend fun sort(client: AppSortClient, active: io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState, apps: List<LaunchableApp>): List<String> {
        val binding = active.resolvedBindings.single()
        return withTimeout(10_000) {
            client.sort(binding.route, binding, apps, "en").getOrThrow().map(LaunchableApp::label)
        }
    }

    private fun sortRequest() = AppSortRequestParcel(
        listOf(AppSortItemParcel(AppKeyParcel("z.pkg", "z.pkg.MainActivity", 1), "Zulu")),
        "en",
    )

    private fun app(packageName: String, label: String) = LaunchableApp(
        AppKey(packageName, "$packageName.MainActivity", 1), label, 0, true, null,
    )

    private class FakeAppRepository(apps: List<LaunchableApp>) : AppRepository {
        override val catalog: StateFlow<AppCatalog> = MutableStateFlow(AppCatalog(1, apps))
        override fun refresh() = Unit
        override suspend fun launch(key: AppKey): Result<Unit> = Result.success(Unit)
        override fun close() = Unit
    }

    companion object {
        private const val MODULE_ID = "modular.test.community.sort"
        private const val MODULE_PACKAGE = "io.github.alexanderwilhelmsenberg.modular.testcommunity"
        private val ASCENDING = listOf("Alpha", "alpha", "beta", "Zulu")
        private val DESCENDING = listOf("Zulu", "beta", "alpha", "Alpha")
        private val CORE_ORDER = listOf("alpha", "Alpha", "beta", "Zulu")
    }
}
