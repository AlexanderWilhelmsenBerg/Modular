package io.github.alexanderwilhelmsenberg.modular.surface

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRouter
import io.github.alexanderwilhelmsenberg.modular.capabilities.CoreHostFactory
import io.github.alexanderwilhelmsenberg.modular.composition.ContextCapabilityBinding
import io.github.alexanderwilhelmsenberg.modular.composition.ContextManager
import io.github.alexanderwilhelmsenberg.modular.composition.DataStoreCompositionStateStore
import io.github.alexanderwilhelmsenberg.modular.composition.InstanceStore
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceNodeParcel
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRegistry
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeSurfaceCrossApkSmokeTest {
    @Test
    fun activeContextRendersAndInvokesExternalHomeSurface() =
        runBlocking {
            val context =
                ApplicationProvider
                    .getApplicationContext<Context>()

            assertSeparateTrustedApk(context)

            val repository = EmptyAppRepository()
            val grantStore =
                DataStoreModuleGrantStateStore(
                    context = context,
                    fileName =
                        "slice8-grants-" +
                            UUID.randomUUID(),
                )
            val securityManager =
                ModuleSecurityManager(
                    identityResolver =
                        AndroidModuleIdentityResolver(
                            context,
                        ),
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
                        "slice8-composition-" +
                            UUID.randomUUID(),
                )
            val instances = InstanceStore(stateStore)
            val contexts = ContextManager(
                stateStore = stateStore,
                routes = router.routes,
            )

            try {
                registry.refresh()

                withTimeout(15_000) {
                    router.routes.first { routes ->
                        routes.any { route ->
                            route.capability ==
                                StandardCapabilities
                                    .SurfaceHome &&
                                route.providerId ==
                                    REFERENCE_MODULE_ID
                        }
                    }
                }

                val instance = instances.create(
                    name = "Minimal Home",
                    providerId = REFERENCE_MODULE_ID,
                ).getOrThrow()

                val launcherContext =
                    contexts.create(
                        name = "Minimal",
                        bindings = listOf(
                            ContextCapabilityBinding(
                                capability =
                                    StandardCapabilities
                                        .SurfaceHome,
                                instanceId =
                                    instance.id,
                            ),
                        ),
                    ).getOrThrow()

                val active = contexts.activate(
                    launcherContext.id,
                ).getOrThrow()
                val route = requireNotNull(
                    active.routeFor(
                        StandardCapabilities
                            .SurfaceHome,
                    ),
                )

                val client = HomeSurfaceClient(router)
                val first = client.render(
                    route = route,
                    instanceId = instance.id,
                ).getOrThrow()

                assertEquals(0L, first.revision)
                assertTrue(
                    first.root.allText().contains(
                        "This surface comes from a " +
                            "separate module APK and " +
                            "is rendered by Core.",
                    ),
                )

                client.performAction(
                    route = route,
                    instanceId = instance.id,
                    actionId = "minimal-home.ping",
                ).getOrThrow()

                val second = client.render(
                    route = route,
                    instanceId = instance.id,
                ).getOrThrow()

                assertEquals(1L, second.revision)
                assertTrue(
                    second.root.allText().contains(
                        "Module action count: 1",
                    ),
                )
            } finally {
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

        assertTrue(
            "Minimal Home must run under " +
                "a distinct Android UID",
            context.applicationInfo.uid !=
                moduleInfo.uid,
        )
        assertEquals(
            "Reference module must share " +
                "the trusted debug signer in CI",
            PackageManager.SIGNATURE_MATCH,
            packageManager.checkSignatures(
                context.packageName,
                REFERENCE_PACKAGE,
            ),
        )
    }

    private fun SurfaceNodeParcel.allText():
        List<String> =
        buildList {
            text?.let(::add)
            children.forEach { child ->
                addAll(child.allText())
            }
        }

    private class EmptyAppRepository :
        AppRepository {
        private val state =
            MutableStateFlow(AppCatalog())

        override val catalog:
            StateFlow<AppCatalog> =
            state

        override fun refresh() = Unit

        override suspend fun launch(
            key: AppKey,
        ): Result<Unit> =
            Result.failure(
                UnsupportedOperationException(
                    "Not used",
                ),
            )

        override fun close() = Unit
    }

    companion object {
        private const val REFERENCE_MODULE_ID =
            "modular.reference.home.minimal"
        private const val REFERENCE_PACKAGE =
            "io.github.alexanderwilhelmsenberg.modular.reference.home.minimal"
    }
}
