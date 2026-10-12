package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSortCrossApkSmokeTest {
    @Test
    fun discoversExternalModuleAndSortsAcrossBinder() = runBlocking {
        val context =
            ApplicationProvider.getApplicationContext<Context>()

        assertSeparateTrustedApk(context)

        val repository = FakeAppRepository(
            listOf(
                app("z.pkg", "Zulu"),
                app("b.pkg", "beta"),
                app("a.pkg", "Alpha"),
            ),
        )

        val hostFactory = CoreHostFactory(
            context = context,
            appRepository = repository,
        )
        val registry = ModuleRegistry(
            context = context,
            hostFactory = hostFactory,
        )
        val router = CapabilityRouter(registry)

        try {
            registry.refresh()

            val route = withTimeout(15_000) {
                router.routes.first { routes ->
                    routes.any { candidate ->
                        candidate.capability ==
                            StandardCapabilities.AppsSort &&
                            candidate.providerId ==
                            REFERENCE_MODULE_ID
                    }
                }.first { candidate ->
                    candidate.capability ==
                        StandardCapabilities.AppsSort &&
                        candidate.providerId ==
                        REFERENCE_MODULE_ID
                }
            }

            val sorted = AppSortClient(router)
                .sort(
                    route = route,
                    apps = repository.catalog.value.apps,
                    localeTag = "en",
                )
                .getOrThrow()

            assertEquals(
                listOf("Alpha", "beta", "Zulu"),
                sorted.map { it.label },
            )
        } finally {
            router.close()
            registry.close()
            repository.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun assertSeparateTrustedApk(context: Context) {
        val packageManager = context.packageManager
        val moduleInfo = packageManager.getApplicationInfo(
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
                activityName = packageName + ".MainActivity",
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

        override val catalog: StateFlow<AppCatalog> = state

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
            "io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical"
    }
}
