package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
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
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRegistry
import io.github.alexanderwilhelmsenberg.modular.security.AndroidModuleIdentityResolver
import io.github.alexanderwilhelmsenberg.modular.security.DataStoreModuleGrantStateStore
import io.github.alexanderwilhelmsenberg.modular.security.ModuleSecurityManager
import io.github.alexanderwilhelmsenberg.modular.security.OfficialModuleBootstrapPolicy
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

@RunWith(AndroidJUnit4::class)
class AppSortCrossApkSmokeTest {
    @Test
    fun switchesDistinctInstancesAndSortsAcrossBinder() =
        runBlocking {
            val context =
                ApplicationProvider
                    .getApplicationContext<Context>()

            assertSeparateTrustedApk(context)

            val repository = FakeAppRepository(
                listOf(
                    app("z.pkg", "Zulu"),
                    app("b.pkg", "beta"),
                    app("a.pkg", "Alpha"),
                    app("0.pkg", "alpha"),
                ),
            )

            val grantStore =
                DataStoreModuleGrantStateStore(
                    context = context,
                    fileName =
                        "slice7-grants-" +
                            UUID.randomUUID(),
                )
            val securityManager = ModuleSecurityManager(
                identityResolver =
                    AndroidModuleIdentityResolver(context),
                grantStore = grantStore,
                officialBootstrapCapabilities =
                    OfficialModuleBootstrapPolicy
                        .capabilities,
            )
            val hostFactory = CoreHostFactory(
                context = context,
                appRepository = repository,
                securityManager = securityManager,
            )
            val registry = ModuleRegistry(
                context = context,
                hostFactory = hostFactory,
            )
            val router = CapabilityRouter(registry)
            val stateStore =
                DataStoreCompositionStateStore(
                    context = context,
                    fileName =
                        "slice5-" + UUID.randomUUID(),
                )
            val instances = InstanceStore(stateStore)
            val contexts = ContextManager(
                stateStore = stateStore,
                routes = router.routes,
            )
            val sortClient = AppSortClient(router)
            var homeSortController: HomeSortController? = null
            var disabledComponent: ComponentName? = null

            try {
                registry.refresh()

                withTimeout(15_000) {
                    router.routes.first { routes ->
                        routes.any { candidate ->
                            candidate.capability ==
                                StandardCapabilities.AppsSort &&
                                candidate.providerId ==
                                REFERENCE_MODULE_ID
                        }
                    }
                }

                val personalInstance = instances.create(
                    name = "Personal sort",
                    providerId = REFERENCE_MODULE_ID,
                    configurationJson = """{"reverse":false}""",
                ).getOrThrow()

                val workInstance = instances.create(
                    name = "Work sort",
                    providerId = REFERENCE_MODULE_ID,
                    configurationJson = """{"reverse":true}""",
                ).getOrThrow()

                assertNotEquals(
                    personalInstance.id,
                    workInstance.id,
                )

                val personalContext = contexts.create(
                    name = "Personal",
                    bindings = listOf(
                        ContextCapabilityBinding(
                            capability =
                                StandardCapabilities.AppsSort,
                            instanceId =
                                personalInstance.id,
                        ),
                    ),
                ).getOrThrow()

                val workContext = contexts.create(
                    name = "Work",
                    bindings = listOf(
                        ContextCapabilityBinding(
                            capability =
                                StandardCapabilities.AppsSort,
                            instanceId =
                                workInstance.id,
                        ),
                    ),
                ).getOrThrow()

                val personalActive = contexts.activate(
                    personalContext.id,
                ).getOrThrow()

                val personalSorted = withTimeout(10_000) {
                    sortClient.sort(
                        route = requireNotNull(
                            personalActive.routeFor(
                                StandardCapabilities.AppsSort,
                            ),
                        ),
                        binding = personalActive.resolvedBindings.single(),
                        apps =
                            repository.catalog.value.apps,
                        localeTag = "en",
                    ).getOrThrow()
                }

                assertEquals(
                    listOf("Alpha", "alpha", "beta", "Zulu"),
                    personalSorted.map { it.label },
                )
                assertEquals(
                    personalInstance.id,
                    personalActive.resolvedBindings
                        .single()
                        .instance
                        .id,
                )

                val workActive = contexts.activate(
                    workContext.id,
                ).getOrThrow()

                assertEquals(
                    workInstance.id,
                    workActive.resolvedBindings
                        .single()
                        .instance
                        .id,
                )

                val workSorted = withTimeout(10_000) {
                    sortClient.sort(
                        route = requireNotNull(
                            workActive.routeFor(
                                StandardCapabilities.AppsSort,
                            ),
                        ),
                        binding = workActive.resolvedBindings.single(),
                        apps =
                            repository.catalog.value.apps,
                        localeTag = "en",
                    ).getOrThrow()
                }

                assertEquals(
                    listOf("Zulu", "beta", "alpha", "Alpha"),
                    workSorted.map { it.label },
                )

                withTimeout(5_000) {
                    stateStore.state.first { state ->
                        state.activeContextId ==
                            workContext.id
                    }
                }

                val invocationCount = AtomicInteger(0)
                val failures = CopyOnWriteArrayList<String>()
                val controller = HomeSortController(
                    catalog = repository.catalog,
                    activeContext = contexts.active,
                    routes = router.routes,
                    sort = { route, binding, apps, locale ->
                        invocationCount.incrementAndGet()
                        sortClient.sort(route, binding, apps, locale)
                    },
                    localeTag = "en",
                    timeoutMillis = 5_000,
                    onFailure = { _, message -> failures.add(message) },
                )
                homeSortController = controller

                // Core's stable-key tie breaker puts 0.pkg first. The external
                // reference helper uses exact label text and puts Alpha first.
                val helperOrder = listOf("Zulu", "beta", "alpha", "Alpha")
                val coreOrder = listOf("alpha", "Alpha", "beta", "Zulu")
                withTimeout(15_000) {
                    controller.orderedApps.first { apps -> apps.map { it.label } == helperOrder }
                }
                assertTrue("Selected helper was not invoked", invocationCount.get() > 0)
                assertTrue("Sort helper failed: $failures", failures.isEmpty())
                controller.orderedApps.value.forEach { sorted ->
                    assertSame(repository.catalog.value.apps.single { it.key == sorted.key }, sorted)
                }

                contexts.deactivate().getOrThrow()
                withTimeout(5_000) {
                    controller.orderedApps.first { apps -> apps.map { it.label } == coreOrder }
                }
                val callsBeforeReactivation = invocationCount.get()

                contexts.activate(workContext.id).getOrThrow()
                withTimeout(15_000) {
                    controller.orderedApps.first { apps -> apps.map { it.label } == helperOrder }
                }
                assertTrue(invocationCount.get() > callsBeforeReactivation)

                val component = requireNotNull(workActive.routeFor(StandardCapabilities.AppsSort)?.component)
                val callsAtDisable = invocationCount.get()
                disabledComponent = component
                registry.setEnabled(component, false)
                withTimeout(5_000) {
                    router.routes.first { available -> available.none { it.component == component } }
                    controller.orderedApps.first { apps -> apps.map { it.label } == coreOrder }
                }
                assertEquals("Disabled helper should not receive more calls", callsAtDisable, invocationCount.get())

                Unit
            } finally {
                homeSortController?.close()
                disabledComponent?.let { registry.setEnabled(it, true) }
                sortClient.close()
                contexts.close()
                instances.close()
                stateStore.close()
                router.close()
                registry.close()
                grantStore.close()
                repository.close()
            }
        }

    @Suppress("DEPRECATION")
    private fun assertSeparateTrustedApk(
        context: Context,
    ) {
        val packageManager = context.packageManager
        val moduleInfo =
            packageManager.getApplicationInfo(
                REFERENCE_PACKAGE,
                0,
            )

        assertNotEquals(
            "Reference module must run under a distinct Android UID",
            context.applicationInfo.uid,
            moduleInfo.uid,
        )

        assertEquals(
            "Reference module must share the trusted debug signer in CI",
            PackageManager.SIGNATURE_MATCH,
            packageManager.checkSignatures(
                context.packageName,
                REFERENCE_PACKAGE,
            ),
        )
    }

    private fun app(
        packageName: String,
        label: String,
    ): LaunchableApp =
        LaunchableApp(
            key = AppKey(
                packageName = packageName,
                activityName =
                    packageName + ".MainActivity",
                profileSerial = 1,
            ),
            label = label,
            firstInstallTime = 0,
            enabled = true,
            loadingProgress = null,
        )

    private class FakeAppRepository(
        apps: List<LaunchableApp>,
    ) : AppRepository {
        private val state = MutableStateFlow(
            AppCatalog(
                generation = 1,
                apps = apps,
            ),
        )

        override val catalog: StateFlow<AppCatalog> =
            state

        override fun refresh() = Unit

        override suspend fun launch(
            key: AppKey,
        ): Result<Unit> =
            Result.success(Unit)

        override fun close() = Unit
    }

    companion object {
        private const val REFERENCE_MODULE_ID =
            "modular.reference.sort.alphabetical"

        private const val REFERENCE_PACKAGE =
            "io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical.debug"
    }
}

