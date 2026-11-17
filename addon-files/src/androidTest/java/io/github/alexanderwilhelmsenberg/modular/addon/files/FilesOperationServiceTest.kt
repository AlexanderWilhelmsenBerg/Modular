package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FilesOperationServiceTest {
    @Test fun foregroundOperationRemainsActiveAfterHomeAndStopsWhenFinished() {
        val context = ApplicationProvider.getApplicationContext<FilesApplication>()
        val id = UUID.randomUUID().toString()
        val lifetime = context.operationLifetime
        assertNull("Another operation is still running in the fixture", lifetime.state)
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun foreground() = manager.getRunningServices(100).any {
            it.service.className == FilesOperationService::class.java.name && it.foreground
        }
        ActivityScenario.launch(FilesActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { assertNull(lifetime.begin(id, "Test storage operation")) }
                lifetime.awaitReady(id, io.github.alexanderwilhelmsenberg.modular.addon.files.storage.Cancellation { false })
                await { foreground() }
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressHome()
                lifetime.update(id, "Verifying item 2/4", 5, 10)
                assertEquals(id, lifetime.state?.id)
                assertEquals(5L, lifetime.state?.done)
                assertTrue(foreground())
                lifetime.finish(id)
                await { !foreground() }
            } finally { lifetime.finish(id) }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(condition())
    }
}
