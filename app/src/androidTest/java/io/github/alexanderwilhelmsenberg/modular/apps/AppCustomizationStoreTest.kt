package io.github.alexanderwilhelmsenberg.modular.apps

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppCustomizationStoreTest {
    @Test fun renameAndHideSurviveStoreRecreationAndRestore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileName = "app-customization-test-${UUID.randomUUID()}"
        val personal = AppKey("example.reader", "example.reader.Main", 1)
        val work = personal.copy(profileSerial = 2)

        fun open(scope: CoroutineScope): AppCustomizationStore = AppCustomizationStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { context.preferencesDataStoreFile(fileName) },
            ),
        )

        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = open(firstScope)
        assertTrue(first.setLabel(personal, "  Books  ").isSuccess)
        assertTrue(first.setHidden(personal, true).isSuccess)
        assertEquals("Books", first.customizations.first().labels[personal.stableId])
        firstScope.coroutineContext[Job]?.cancelAndJoin()

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val second = open(secondScope)
            val restored = second.customizations.first()
            assertEquals("Books", restored.labels[personal.stableId])
            assertTrue(restored.isHidden(personal))
            assertFalse(restored.isHidden(work))

            assertTrue(second.setLabel(personal, " ").isSuccess)
            assertTrue(second.setHidden(personal, false).isSuccess)
            val reset = second.customizations.first { !it.isHidden(personal) }
            assertEquals(emptyMap<String, String>(), reset.labels)
            assertEquals(emptySet<String>(), reset.hidden)
        } finally {
            secondScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
