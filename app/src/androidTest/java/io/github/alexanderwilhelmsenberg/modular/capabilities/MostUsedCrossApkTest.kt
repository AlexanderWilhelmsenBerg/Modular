package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.composition.ContextCapabilityBinding
import io.github.alexanderwilhelmsenberg.modular.composition.ContextManager
import io.github.alexanderwilhelmsenberg.modular.composition.DataStoreCompositionStateStore
import io.github.alexanderwilhelmsenberg.modular.composition.InstanceStore
import io.github.alexanderwilhelmsenberg.modular.home.HomeSortController
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRegistry
import io.github.alexanderwilhelmsenberg.modular.security.AndroidModuleIdentityResolver
import io.github.alexanderwilhelmsenberg.modular.security.DataStoreModuleGrantStateStore
import io.github.alexanderwilhelmsenberg.modular.security.ModuleSecurityManager
import io.github.alexanderwilhelmsenberg.modular.security.OfficialModuleBootstrapPolicy
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the separately installed :addon-most-used debug APK. */
@RunWith(AndroidJUnit4::class)
class MostUsedCrossApkTest {
    @Test
    fun usageAccessControlsRemoteSortAndCoreFallback(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val moduleInfo = context.packageManager.getApplicationInfo(MODULE_PACKAGE, 0)
        assertNotEquals("Most Used must be a separate APK", context.applicationInfo.uid, moduleInfo.uid)
        assertEquals(
            "CI must install the matching debug signer",
            PackageManager.SIGNATURE_MATCH,
            context.packageManager.checkSignatures(context.packageName, MODULE_PACKAGE),
        )

        val originalMode = usageMode(context, moduleInfo.uid)
        val repository = FakeAppRepository(
            listOf(
                app("a.mostused.fixture", "Zulu"),
                app("0.mostused.fixture", "alpha"),
                app("z.mostused.fixture", "Alpha"),
            ),
        )
        val grantStore = DataStoreModuleGrantStateStore(context, "most-used-grants-${UUID.randomUUID()}")
        val security = ModuleSecurityManager(
            AndroidModuleIdentityResolver(context), grantStore,
            OfficialModuleBootstrapPolicy.capabilities,
        )
        val registry = ModuleRegistry(context, CoreHostFactory(context, repository, security))
        val router = CapabilityRouter(registry)
        val stateStore = DataStoreCompositionStateStore(context, "most-used-composition-${UUID.randomUUID()}")
        val instances = InstanceStore(stateStore, router.routes)
        val contexts = ContextManager(stateStore, router.routes)
        val client = AppSortClient(router)
        var controller: HomeSortController? = null

        try {
            setUsageAccess("ignore")
            assertEquals(AppOpsManager.MODE_IGNORED, usageMode(context, moduleInfo.uid))
            registry.refresh()
            val route = withTimeout(15_000) {
                router.routes.first { routes -> routes.any {
                    it.providerId == MODULE_ID && it.capability == StandardCapabilities.AppsSort
                } }.single { it.providerId == MODULE_ID && it.capability == StandardCapabilities.AppsSort }
            }
            assertEquals(MODULE_PACKAGE, requireNotNull(route.component).packageName)

            val instance = instances.create("Most Used", MODULE_ID, """{"days":7}""").getOrThrow()
            val launcherContext = contexts.create(
                "Most Used", listOf(ContextCapabilityBinding(StandardCapabilities.AppsSort, instance.id)),
            ).getOrThrow()
            val active = contexts.activate(launcherContext.id).getOrThrow()
            val source = repository.catalog.value.apps
            val denied = withTimeout(10_000) {
                client.sort(route, active.resolvedBindings.single(), source, "en")
            }
            assertTrue("Denied Usage Access must fail the sort call", denied.isFailure)

            val failures = CopyOnWriteArrayList<String>()
            val calls = AtomicInteger()
            val successes = AtomicInteger()
            val homeController = HomeSortController(
                catalog = repository.catalog,
                activeContext = contexts.active,
                routes = router.routes,
                sort = { selectedRoute, binding, apps, locale ->
                    calls.incrementAndGet()
                    client.sort(selectedRoute, binding, apps, locale).also {
                        if (it.isSuccess) successes.incrementAndGet()
                    }
                },
                localeTag = "en",
                timeoutMillis = 5_000,
                onFailure = { _, message -> failures.add(message) },
            )
            controller = homeController
            withTimeout(15_000) { homeController.orderedApps.first { it.map(LaunchableApp::label) == CORE_ORDER } }
            withTimeout(15_000) { while (failures.isEmpty()) kotlinx.coroutines.delay(25) }
            assertTrue("Home must invoke the selected add-on", calls.get() > 0)

            setUsageAccess("allow")
            assertEquals(AppOpsManager.MODE_ALLOWED, usageMode(context, moduleInfo.uid))
            // These fixture packages have no usage history, so the provider's
            // successful zero-data result should be alphabetical.
            val zeroData = withTimeout(10_000) {
                client.sort(route, active.resolvedBindings.single(), source, "en").getOrThrow()
            }
            assertEquals(HELPER_ZERO_DATA_ORDER, zeroData.map(LaunchableApp::label))
            zeroData.forEach { sorted -> assertSame(source.single { it.key == sorted.key }, sorted) }

            // Exercise a real UsageStats query with installed package names.
            // Foreground time on an emulator is device-dependent; the contract
            // here is an exact permutation of the current Core app objects.
            val profileSerial = context.getSystemService(UserManager::class.java)
                .getSerialNumberForUser(Process.myUserHandle())
            val installed = listOf(
                app(context.packageName, "Core", profileSerial),
                app(MODULE_PACKAGE, "Most Used", profileSerial),
            )
            val queried = withTimeout(10_000) {
                client.sort(route, active.resolvedBindings.single(), installed, "en").getOrThrow()
            }
            assertEquals(installed.map { it.key }.toSet(), queried.map { it.key }.toSet())
            assertEquals(installed.size, queried.size)
            queried.forEach { sorted -> assertSame(installed.single { it.key == sorted.key }, sorted) }

            // Android's app-op change is not a Core StateFlow input. A new
            // catalog generation causes Home to request a fresh sort.
            repository.publish()
            withTimeout(15_000) {
                homeController.orderedApps.first { it.map(LaunchableApp::label) == HELPER_ZERO_DATA_ORDER }
            }
            assertTrue("Home should accept the add-on's successful result", successes.get() > 0)

            setUsageAccess("ignore")
            val revoked = withTimeout(10_000) {
                client.sort(route, active.resolvedBindings.single(), source, "en")
            }
            assertTrue("Revoked Usage Access must fail the sort call", revoked.isFailure)
            repository.publish()
            withTimeout(15_000) { while (failures.size < 2) kotlinx.coroutines.delay(25) }
            assertEquals(CORE_ORDER, homeController.orderedApps.value.map(LaunchableApp::label))
            Unit
        } finally {
            controller?.close()
            client.close()
            contexts.close()
            instances.close()
            stateStore.close()
            router.close()
            registry.close()
            grantStore.close()
            repository.close()
            setUsageAccess(modeName(originalMode))
        }
    }

    private fun setUsageAccess(mode: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appops set $MODULE_PACKAGE GET_USAGE_STATS $mode",
        )
        FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        descriptor.close()
    }

    private fun usageMode(context: Context, uid: Int): Int {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.GET_APP_OPS_STATS")
        return try {
            context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, uid, MODULE_PACKAGE,
            )
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private fun modeName(mode: Int): String = when (mode) {
        AppOpsManager.MODE_ALLOWED -> "allow"
        AppOpsManager.MODE_IGNORED -> "ignore"
        AppOpsManager.MODE_ERRORED -> "deny"
        AppOpsManager.MODE_DEFAULT -> "default"
        AppOpsManager.MODE_FOREGROUND -> "foreground"
        else -> error("Unknown initial Usage Access app-op mode: $mode")
    }

    private fun app(packageName: String, label: String, profileSerial: Long = 1L) = LaunchableApp(
        AppKey(packageName, "$packageName.MainActivity", profileSerial), label, 0, true, null,
    )

    private class FakeAppRepository(apps: List<LaunchableApp>) : AppRepository {
        private val state = MutableStateFlow(AppCatalog(generation = 1, apps = apps))
        override val catalog: StateFlow<AppCatalog> = state
        fun publish() {
            state.value = state.value.copy(generation = state.value.generation + 1)
        }
        override fun refresh() = Unit
        override suspend fun launch(key: AppKey): Result<Unit> = Result.success(Unit)
        override fun close() = Unit
    }

    private companion object {
        const val MODULE_ID = "modular.addon.sort.mostused"
        const val MODULE_PACKAGE = "io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused.debug"
        val CORE_ORDER = listOf("alpha", "Alpha", "Zulu")
        val HELPER_ZERO_DATA_ORDER = listOf("Alpha", "alpha", "Zulu")
    }
}
