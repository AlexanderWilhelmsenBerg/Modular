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
import io.github.alexanderwilhelmsenberg.modular.home.HomeFeaturedController
import io.github.alexanderwilhelmsenberg.modular.home.HomeFeaturedState
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedMutationResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedPinRequestParcel
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRegistry
import io.github.alexanderwilhelmsenberg.modular.security.AndroidModuleIdentityResolver
import io.github.alexanderwilhelmsenberg.modular.security.DataStoreModuleGrantStateStore
import io.github.alexanderwilhelmsenberg.modular.security.ModuleSecurityManager
import io.github.alexanderwilhelmsenberg.modular.security.OfficialModuleBootstrapPolicy
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires separately installed :addon-most-used debug APK with the matching debug signer. */
@RunWith(AndroidJUnit4::class)
class FeaturedAppsCrossApkTest {
    @Test fun normalSettingsSelectsFeaturedAndPinsWorkWithoutUsageAccess(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val moduleInfo = context.packageManager.getApplicationInfo(MODULE_PACKAGE, 0)
        assertNotEquals(context.applicationInfo.uid, moduleInfo.uid)
        assertEquals(PackageManager.SIGNATURE_MATCH,
            context.packageManager.checkSignatures(context.packageName, MODULE_PACKAGE))
        val originalMode = usageMode(context, moduleInfo.uid)
        val profileSerial = context.getSystemService(UserManager::class.java)
            .getSerialNumberForUser(Process.myUserHandle())
        assertTrue(profileSerial >= 0)
        val first = app("fixture.featured.alpha", "Alpha")
        val second = app("fixture.featured.zulu", "Zulu")
        val used = app(TEST_LAUNCHER_PACKAGE, "Zulu used", profileSerial)
        val unused = app("fixture.featured.unused", "Alpha unused", profileSerial)
        val repository = FakeAppRepository(listOf(first, second, used, unused))
        val grants = DataStoreModuleGrantStateStore(context, "featured-grants-${UUID.randomUUID()}")
        val security = ModuleSecurityManager(AndroidModuleIdentityResolver(context), grants,
            OfficialModuleBootstrapPolicy.capabilities)
        val registry = ModuleRegistry(context, CoreHostFactory(context, repository, security))
        val router = CapabilityRouter(registry)
        val stateStore = DataStoreCompositionStateStore(context, "featured-composition-${UUID.randomUUID()}")
        val contexts = ContextManager(stateStore, router.routes)
        val settings = ModuleSettingsController(stateStore, router.routes)
        val client = FeaturedAppsClient(router)
        var featured: HomeFeaturedController? = null

        try {
            setUsageAccess("ignore")
            registry.refresh()
            val route = withTimeout(15_000) {
                router.routes.first { routes -> routes.any {
                    it.providerId == MODULE_ID && it.capability == StandardCapabilities.AppsFeatured
                } }.single { it.providerId == MODULE_ID && it.capability == StandardCapabilities.AppsFeatured }
            }
            assertEquals(MODULE_PACKAGE, requireNotNull(route.component).packageName)

            settings.save(MODULE_ID, "Most Used", "{}", true).getOrThrow()
            val active = withTimeout(10_000) {
                contexts.active.first { current -> current.isReady &&
                    current.resolvedBindings.singleOrNull()?.let {
                        it.capability == StandardCapabilities.AppsFeatured && it.instance.providerId == MODULE_ID
                    } == true
                }
            }
            assertTrue(settings.isUsedForAppOrder(MODULE_ID))
            assertEquals(7, org.json.JSONObject(active.resolvedBindings.single().instance.configurationJson).getInt("days"))
            val binding = active.resolvedBindings.single()
            val home = HomeFeaturedController(repository.catalog, contexts.active, router.routes, client, "en")
            featured = home

            // An earlier test run may have left pins in the add-on's private, per-instance store.
            // Remove them through the public contract so this cross-APK test starts deterministically.
            var state = withTimeout(10_000) { home.featured.first { it != null } }!!
            for (oldPin in state.pinnedKeys) {
                home.setPinned(oldPin, false).getOrThrow()
                state = withTimeout(10_000) {
                    home.featured.first { it != null && oldPin !in it.pinnedKeys }
                }!!
            }
            assertEquals(FeaturedAppsResultParcel.STATE_PERMISSION_REQUIRED, state.featureState)
            assertTrue(state.suggestedApps.isEmpty())

            home.setPinned(first.key, true).getOrThrow()
            val afterFirst = withTimeout(10_000) {
                home.featured.first { it?.pinnedKeys == listOf(first.key) }
            }!!
            assertEquals(FeaturedAppsResultParcel.STATE_PERMISSION_REQUIRED, afterFirst.featureState)
            assertEquals(listOf(first.key), afterFirst.pinnedApps.map { it.key })
            assertTrue(afterFirst.suggestedApps.isEmpty())

            // A stale edit changing an already saved pin must receive a typed conflict.
            val stale = client.setPinned(route, binding, FeaturedPinRequestParcel(
                UUID.randomUUID().toString(), repository.catalog.value.generation,
                afterFirst.featureRevision - 1, first.key.toParcel(), false,
            )).getOrThrow()
            assertEquals(FeaturedMutationResultParcel.STATUS_CONFLICT, stale.status)
            assertEquals(afterFirst.featureRevision, stale.featureRevision)

            home.setPinned(second.key, true).getOrThrow()
            home.reorderPins(listOf(second.key, first.key)).getOrThrow()
            val reordered = withTimeout(10_000) {
                home.featured.first { it?.pinnedKeys == listOf(second.key, first.key) }
            }!!
            assertEquals(listOf(second.key, first.key), reordered.pinnedApps.map { it.key })
            assertTrue(reordered.suggestedApps.isEmpty())

            // A second v4 session reacquires the same instance endpoint; its
            // separate Binder must read the pins committed by the first session.
            val freshRegistry = ModuleRegistry(context, CoreHostFactory(context, repository, security))
            val freshRouter = CapabilityRouter(freshRegistry)
            val freshClient = FeaturedAppsClient(freshRouter)
            try {
                freshRegistry.refresh()
                val freshRoute = withTimeout(15_000) {
                    freshRouter.routes.first { routes -> routes.any {
                        it.providerId == MODULE_ID && it.capability == StandardCapabilities.AppsFeatured
                    } }.single { it.providerId == MODULE_ID && it.capability == StandardCapabilities.AppsFeatured }
                }
                assertNotEquals("New session should acquire a new endpoint",
                    route.sessionId, freshRoute.sessionId)
                val reread = freshClient.getFeaturedApps(freshRoute, binding.copy(route = freshRoute),
                    FeaturedAppsRequestParcel(UUID.randomUUID().toString(), repository.catalog.value.generation,
                        "en", repository.catalog.value.apps.map {
                            AppSortItemParcel(it.key.toParcel(), it.label)
                        })).getOrThrow()
                assertEquals(FeaturedAppsResultParcel.STATUS_SUCCESS, reread.status)
                assertEquals(listOf(second.key.toParcel(), first.key.toParcel()), reread.pinnedKeys)
                assertEquals(FeaturedAppsResultParcel.STATE_PERMISSION_REQUIRED, reread.featureState)
            } finally {
                freshClient.close()
                freshRouter.close()
                freshRegistry.close()
            }

            // App-op changes are not a StateFlow input, so refresh the catalog to force
            // a new real Binder read after granting and then revoking Usage Access.
            setUsageAccess("allow")
            foregroundTestLauncher()
            var ranked: HomeFeaturedState? = null
            withTimeout(30_000) {
                while (ranked == null) {
                    repository.publish()
                    ranked = kotlinx.coroutines.withTimeoutOrNull(1_500) {
                        home.featured.first { it?.suggestedApps?.firstOrNull()?.key == used.key }
                    }
                    if (ranked == null) delay(200)
                }
            }
            assertEquals(FeaturedAppsResultParcel.STATE_READY, ranked!!.featureState)
            assertEquals(used.key, ranked!!.suggestedApps.first().key)
            assertTrue(unused.key !in ranked!!.suggestedApps.map { it.key })
            assertEquals(listOf(second.key, first.key), ranked!!.pinnedKeys)
            setUsageAccess("ignore")
            repository.publish()
            val revoked = withTimeout(10_000) {
                home.featured.first { current ->
                    current?.let {
                        it.featureState == FeaturedAppsResultParcel.STATE_PERMISSION_REQUIRED &&
                            it.pinnedKeys == listOf(second.key, first.key)
                    } == true
                }
            }!!
            assertEquals(listOf(second.key, first.key), revoked.pinnedApps.map { it.key })
            assertTrue(revoked.suggestedApps.isEmpty())
        } finally {
            // Leave the add-on's managed instance clean for repeated test runs.
            featured?.let { home ->
                runCatching {
                    val pins = home.featured.value?.pinnedKeys.orEmpty()
                    pins.forEach { home.setPinned(it, false).getOrThrow() }
                }
                home.close()
            }
            client.close()
            contexts.close()
            stateStore.close()
            router.close()
            registry.close()
            grants.close()
            repository.close()
            setUsageAccess(modeName(originalMode))
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return try { FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() } }
        finally { descriptor.close() }
    }

    private fun setUsageAccess(mode: String) { shell("appops set $MODULE_PACKAGE GET_USAGE_STATS $mode") }

    private suspend fun foregroundTestLauncher() {
        val started = shell("am start -W -n $TEST_LAUNCHER_PACKAGE/android.app.Activity")
        assertTrue("Could not start independent launcher fixture: $started", started.contains("Status: ok"))
        try { delay(2_500) }
        finally { shell("input keyevent KEYCODE_HOME") }
    }

    private fun usageMode(context: Context, uid: Int): Int {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.GET_APP_OPS_STATS")
        return try {
            context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, uid, MODULE_PACKAGE)
        } finally { automation.dropShellPermissionIdentity() }
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
        AppKey(packageName, "$packageName.MainActivity", profileSerial), label, 0, true, null)

    private class FakeAppRepository(apps: List<LaunchableApp>) : AppRepository {
        private val state = MutableStateFlow(AppCatalog(generation = 1, apps = apps))
        override val catalog: StateFlow<AppCatalog> = state
        fun publish() { state.value = state.value.copy(generation = state.value.generation + 1) }
        override fun refresh() = Unit
        override suspend fun launch(key: AppKey): Result<Unit> = Result.success(Unit)
        override fun close() = Unit
    }

    private fun AppKey.toParcel() =
        io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel(
            packageName, activityName, profileSerial)

    private companion object {
        const val MODULE_ID = "modular.addon.sort.mostused"
        const val MODULE_PACKAGE = "io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused.debug"
        const val TEST_LAUNCHER_PACKAGE = "io.github.alexanderwilhelmsenberg.modular.testlauncher"
    }
}
