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
import io.github.alexanderwilhelmsenberg.modular.composition.ContextManager
import io.github.alexanderwilhelmsenberg.modular.composition.DataStoreCompositionStateStore
import io.github.alexanderwilhelmsenberg.modular.composition.ModuleSettingsController
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
        val contexts = ContextManager(stateStore, router.routes)
        // Keep this compatibility test on the old apps.sort/v1 selection path.
        // Ordinary Settings now prefers the newly advertised featured endpoint.
        val legacyRoutes = MutableStateFlow(router.routes.value.filterNot {
            it.capability == StandardCapabilities.AppsFeatured
        })
        val settings = ModuleSettingsController(stateStore, legacyRoutes)
        val client = AppSortClient(router)
        var controller: HomeSortController? = null

        try {
            setUsageAccess("ignore")
            assertEquals(AppOpsManager.MODE_IGNORED, usageMode(context, moduleInfo.uid))
            registry.refresh()
            val route = requireNotNull(withTimeoutOrNull(15_000) {
                router.routes.first { routes -> routes.any {
                    it.providerId == MODULE_ID && it.capability == StandardCapabilities.AppsSort
                } }.single { it.providerId == MODULE_ID && it.capability == StandardCapabilities.AppsSort }
            }) { "Most Used apps.sort route did not appear within 15 s; routes=${router.routes.value}" }
            assertEquals(MODULE_PACKAGE, requireNotNull(route.component).packageName)
            legacyRoutes.value = router.routes.value.filterNot {
                it.capability == StandardCapabilities.AppsFeatured
            }

            settings.save(MODULE_ID, "Most Used", "{}", true).getOrThrow()
            val active = requireNotNull(withTimeoutOrNull(10_000) {
                contexts.active.first { current ->
                    current.isReady && current.resolvedBindings.any { binding ->
                        binding.capability == StandardCapabilities.AppsSort &&
                            binding.instance.providerId == MODULE_ID &&
                            org.json.JSONObject(binding.instance.configurationJson).optInt("days") == 7
                    }
                }
            }) { "Settings did not activate Most Used defaults; active=${contexts.active.value}" }
            assertTrue("Settings must select Most Used for Home", settings.isUsedForAppOrder(MODULE_ID))
            assertEquals(
                "Saving defaults must activate the descriptor's seven-day value",
                7,
                org.json.JSONObject(active.resolvedBindings.single().instance.configurationJson).getInt("days"),
            )
            assertEquals(
                "Settings must return the saved configuration",
                7,
                org.json.JSONObject(settings.configurationFor(MODULE_ID)).getInt("days"),
            )
            val source = repository.catalog.value.apps
            val denied = requireNotNull(withTimeoutOrNull(10_000) {
                client.sort(route, active.resolvedBindings.single(), source, "en")
            }) { "Most Used denied-access sort did not return within 10 s" }
            assertTrue(
                "Denied Usage Access must fail the sort call; result=$denied",
                denied.isFailure && denied.exceptionOrNull()?.message.orEmpty()
                    .contains("usage access", ignoreCase = true),
            )

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
            assertHomeOrder(homeController, CORE_ORDER, "Denied access")
            awaitCondition(15_000, "Home did not report denied Usage Access; calls=${calls.get()}") {
                failures.isNotEmpty()
            }
            assertTrue("Home must invoke the selected add-on", calls.get() > 0)

            settings.save(MODULE_ID, "Most Used", """{"days":1}""", true).getOrThrow()
            val changed = requireNotNull(withTimeoutOrNull(10_000) {
                contexts.active.first { current ->
                    current.isReady && current.resolvedBindings.singleOrNull()?.let { binding ->
                        binding.instance.providerId == MODULE_ID &&
                            org.json.JSONObject(binding.instance.configurationJson).optInt("days") == 1
                    } == true
                }
            }) { "Settings did not activate the one-day value; active=${contexts.active.value}" }
            assertTrue("Changing days must leave Most Used selected", settings.isUsedForAppOrder(MODULE_ID))
            assertEquals(1, org.json.JSONObject(settings.configurationFor(MODULE_ID)).getInt("days"))
            assertEquals(MODULE_ID, changed.resolvedBindings.single().instance.providerId)

            setUsageAccess("allow")
            assertEquals(AppOpsManager.MODE_ALLOWED, usageMode(context, moduleInfo.uid))
            // These fixture packages have no usage history, so the provider's
            // successful zero-data result should be alphabetical.
            val zeroData = requireNotNull(withTimeoutOrNull(10_000) {
                client.sort(route, changed.resolvedBindings.single(), source, "en").getOrThrow()
            }) { "Most Used zero-data sort did not return within 10 s" }
            assertEquals(HELPER_ZERO_DATA_ORDER, zeroData.map(LaunchableApp::label))
            zeroData.forEach { sorted -> assertSame(source.single { it.key == sorted.key }, sorted) }

            // A real, foregrounded package must beat an unused fake package
            // despite having a later alphabetic label. The wrong-profile case
            // must stay alphabetical even when the package has usage history.
            val profileSerial = context.getSystemService(UserManager::class.java)
                .getSerialNumberForUser(Process.myUserHandle())
            assertTrue("Current Android profile has no valid serial", profileSerial >= 0)
            val used = app(TEST_LAUNCHER_PACKAGE, "Zulu used", profileSerial)
            val unused = app("a.mostused.fixture.uninstalled", "Alpha unused", profileSerial)
            val installed = listOf(used, unused)
            val oppositeProfile = listOf(
                used.copy(key = used.key.copy(profileSerial = profileSerial + 1)),
                unused.copy(key = unused.key.copy(profileSerial = profileSerial + 1)),
            )
            foregroundTestLauncher()
            var observed: List<LaunchableApp> = emptyList()
            val queried = withTimeoutOrNull(30_000) {
                while (true) {
                    observed = client.sort(
                        route, changed.resolvedBindings.single(), installed, "en",
                    ).getOrThrow()
                    if (observed.firstOrNull()?.key == used.key) break
                    delay(300)
                }
                observed
            }
            assertTrue(
                "Most Used did not rank foregrounded $TEST_LAUNCHER_PACKAGE above unused " +
                    "fixture after 30 s; last order=${observed.map(LaunchableApp::label)}",
                queried != null,
            )
            val observedOrder = requireNotNull(queried)
            assertEquals(installed.map { it.key }.toSet(), observedOrder.map { it.key }.toSet())
            assertEquals(installed.size, observedOrder.size)
            observedOrder.forEach { sorted -> assertSame(installed.single { it.key == sorted.key }, sorted) }
            val oppositeResult = requireNotNull(withTimeoutOrNull(10_000) {
                client.sort(route, changed.resolvedBindings.single(), oppositeProfile, "en").getOrThrow()
            }) { "Most Used opposite-profile sort did not return within 10 s" }
            assertEquals(
                "Usage from this profile must not reorder another profile",
                oppositeProfile.map { it.key }.reversed(),
                oppositeResult.map { it.key },
            )

            // Android's app-op change is not a Core StateFlow input. A new
            // catalog generation causes Home to request a fresh sort.
            val grantedGeneration = repository.publish()
            val grantedOrder = withTimeoutOrNull(15_000) {
                homeController.orderedApps.first { ordered ->
                    ordered.map(LaunchableApp::label) == HELPER_ZERO_DATA_ORDER &&
                        ordered.all { it.firstInstallTime == grantedGeneration }
                }
            }
            assertTrue(
                "Granted access: Home did not accept catalog generation $grantedGeneration; " +
                    "order=${homeController.orderedApps.value.map(LaunchableApp::label)}, " +
                    "calls=${calls.get()}, failures=$failures",
                grantedOrder != null,
            )
            awaitCondition(15_000, "Granted access: Home did not activate the helper order") {
                homeController.customOrderActive.value
            }
            assertTrue("Home should accept the add-on's successful result", successes.get() > 0)

            setUsageAccess("ignore")
            val revoked = requireNotNull(withTimeoutOrNull(10_000) {
                client.sort(route, changed.resolvedBindings.single(), source, "en")
            }) { "Most Used revoked-access sort did not return within 10 s" }
            assertTrue(
                "Revoked Usage Access must fail the sort call; result=$revoked",
                revoked.isFailure && revoked.exceptionOrNull()?.message.orEmpty()
                    .contains("usage access", ignoreCase = true),
            )
            val failureCountBeforePublish = failures.size
            val revokedGeneration = repository.publish()
            val reportedRevocation = withTimeoutOrNull(15_000) {
                while (failures.size <= failureCountBeforePublish) delay(25)
                true
            }
            assertTrue(
                "Home did not report revoked Usage Access for catalog generation $revokedGeneration; " +
                    "failures=$failures, calls=${calls.get()}, " +
                    "order=${homeController.orderedApps.value.map(LaunchableApp::label)}, " +
                    "customOrderActive=${homeController.customOrderActive.value}",
                reportedRevocation == true,
            )
            assertEquals(CORE_ORDER, homeController.orderedApps.value.map(LaunchableApp::label))
            Unit
        } finally {
            controller?.close()
            client.close()
            contexts.close()
            stateStore.close()
            router.close()
            registry.close()
            grantStore.close()
            repository.close()
            setUsageAccess(modeName(originalMode))
        }
    }

    private suspend fun assertHomeOrder(
        controller: HomeSortController,
        expected: List<String>,
        phase: String,
    ) {
        val matched = withTimeoutOrNull(15_000) {
            controller.orderedApps.first { it.map(LaunchableApp::label) == expected }
        }
        assertTrue(
            "$phase: expected Home order $expected, got " +
                controller.orderedApps.value.map(LaunchableApp::label),
            matched != null,
        )
    }

    private suspend fun awaitCondition(timeoutMillis: Long, message: String, condition: () -> Boolean) {
        val met = withTimeoutOrNull(timeoutMillis) {
            while (!condition()) delay(25)
            true
        }
        assertTrue(message, met == true)
    }

    private suspend fun foregroundTestLauncher() {
        val started = shell("am start -W -n $TEST_LAUNCHER_PACKAGE/android.app.Activity")
        assertTrue("Could not start independent launcher fixture: $started", started.contains("Status: ok"))
        try {
            delay(2_500)
        } finally {
            shell("input keyevent KEYCODE_HOME")
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return try {
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        } finally {
            descriptor.close()
        }
    }

    private fun setUsageAccess(mode: String) {
        shell("appops set $MODULE_PACKAGE GET_USAGE_STATS $mode")
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
        fun publish(): Long {
            val generation = state.value.generation + 1
            // Unique per-publish metadata proves Home accepted this generation,
            // even when labels and keys happen to have an earlier matching order.
            state.value = state.value.copy(generation = generation,
                apps = state.value.apps.map { it.copy(firstInstallTime = generation) })
            return generation
        }
        override fun refresh() = Unit
        override suspend fun launch(key: AppKey): Result<Unit> = Result.success(Unit)
        override fun close() = Unit
    }

    private companion object {
        const val MODULE_ID = "modular.addon.sort.mostused"
        const val MODULE_PACKAGE = "io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused.debug"
        const val TEST_LAUNCHER_PACKAGE = "io.github.alexanderwilhelmsenberg.modular.testlauncher"
        val CORE_ORDER = listOf("alpha", "Alpha", "Zulu")
        val HELPER_ZERO_DATA_ORDER = listOf("Alpha", "alpha", "Zulu")
    }
}
