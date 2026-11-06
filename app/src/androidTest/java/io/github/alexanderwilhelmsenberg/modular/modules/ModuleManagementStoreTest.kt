package io.github.alexanderwilhelmsenberg.modular.modules

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModuleManagementStoreTest {
    private lateinit var context: Context

    @Before
    fun clearPreferences() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("module_management", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun enablementAndKnownProviderIdSurviveStoreRecreation() {
        val component = ComponentName("example.module", "example.module.Service")
        ModuleManagementStore(context).apply {
            setEnabled(component, false)
            rememberProviderId(component, "example.provider")
        }

        val restored = ModuleManagementStore(context)
        assertFalse(restored.isEnabled(component))
        assertEquals("example.provider", restored.knownProviderId(component))

        restored.setEnabled(component, true)
        assertTrue(ModuleManagementStore(context).isEnabled(component))
    }

    @Test
    fun eventLogPersistsAndRetainsOnlyMostRecentTwoHundredEntries() {
        val store = ModuleManagementStore(context)
        repeat(205) { index ->
            store.record("example.module/.Service", "retry", "attempt-$index", isError = index % 2 == 0)
        }

        val events = ModuleManagementStore(context).events.value
        assertEquals(200, events.size)
        assertEquals("attempt-5", events.first().message)
        assertEquals("attempt-204", events.last().message)
        assertFalse(events.first().isError)

        val restored = ModuleManagementStore(context)
        restored.clearEvents()
        assertTrue(ModuleManagementStore(context).events.value.isEmpty())
        assertNull(ModuleManagementStore(context).knownProviderId(ComponentName("missing", "missing.Service")))
    }

    @Test
    fun eventFieldsAreBoundedBeforePersistence() {
        val store = ModuleManagementStore(context)
        store.record("c".repeat(800), "s".repeat(100), "m".repeat(3_000))

        val event = ModuleManagementStore(context).events.value.single()
        assertEquals(512, event.component.length)
        assertEquals(64, event.stage.length)
        assertEquals(2_048, event.message.length)
    }
}
