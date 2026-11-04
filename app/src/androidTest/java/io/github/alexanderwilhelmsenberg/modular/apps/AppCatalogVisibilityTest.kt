package io.github.alexanderwilhelmsenberg.modular.apps

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCatalogVisibilityTest {
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
                    it.key.packageName == "io.github.alexanderwilhelmsenberg.modular.testlauncher"
                },
            )
        } finally {
            repository.close()
        }
    }
}

