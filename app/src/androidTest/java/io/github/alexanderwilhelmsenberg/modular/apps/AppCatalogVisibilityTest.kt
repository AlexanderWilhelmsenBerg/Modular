package io.github.alexanderwilhelmsenberg.modular.apps

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCatalogVisibilityTest {
    private val fixturePackage = "io.github.alexanderwilhelmsenberg.modular.testlauncher"

    @Test
    fun catalogIncludesAnIndependentInstalledLauncherApp() = runBlocking {
        // Installed by CI before instrumentation, never started or bound by Core.
        // Query from targetContext: the test APK's visibility must not mask Core's.
        val repository = AndroidAppRepository(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        try {
            val catalog = withTimeout(10_000) {
                repository.catalog.first { it.generation > 0 && !it.isRefreshing }
            }
            assertTrue(
                "Core cannot discover the independently installed launcher fixture",
                catalog.apps.any {
                    it.key.packageName == fixturePackage
                },
            )
        } finally {
            repository.close()
        }
    }

    @Test
    fun warmStartPublishesCachedSnapshotBeforeReconciliation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = AndroidAppRepository(context)
        val initial = try {
            withTimeout(10_000) {
                first.catalog.first { it.generation > 0 && !it.isRefreshing }
            }.also { live ->
                withTimeout(10_000) {
                    while (true) {
                        val snapshot = AppCatalogCache(context).read()
                        if (snapshot?.isUsable(
                                context.resources.configuration.locales.toLanguageTags(),
                                live.profiles,
                            ) == true && snapshot.apps == live.apps
                        ) break
                        delay(20)
                    }
                }
            }
        } finally {
            first.close()
        }

        val snapshots = LinkedBlockingQueue<AppCatalog>()
        val second = AndroidAppRepository(context) { snapshots.offer(it) }
        try {
            val cached = withContext(Dispatchers.IO) {
                snapshots.poll(10, TimeUnit.SECONDS)
            }
            assertNotNull("Warm catalog was never published", cached)
            assertTrue(cached!!.isRefreshing)
            assertEquals(initial.apps, cached.apps)
            assertEquals(initial.profiles, cached.profiles)
            val reconciled = withTimeout(10_000) {
                second.catalog.first { !it.isRefreshing && it.generation > 0 }
            }
            assertTrue(reconciled.apps.any { it.key.packageName == fixturePackage })
            if (reconciled.apps == cached.apps && reconciled.profiles == cached.profiles) {
                assertEquals(cached.generation, reconciled.generation)
            }
        } finally {
            second.close()
        }
    }
}

