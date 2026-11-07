package io.github.alexanderwilhelmsenberg.modular.mostused

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LegacyMostUsedMigrationTest {
    @Test
    fun contextDetachRunsOnlyAfterSuccessfulSettingsCommit() = runBlocking {
        val events = mutableListOf<String>()
        val result = saveBeforeDetach(
            save = {
                events += "settings committed"
                Result.success(Unit)
            },
            detach = {
                events += "binding detached"
                Result.success(true)
            },
        )

        assertEquals(listOf("settings committed", "binding detached"), events)
        assertEquals(true, result.getOrThrow())
    }

    @Test
    fun failedSettingsCommitLeavesBindingSelected() = runBlocking {
        var detached = false
        val result = runCatching {
            saveBeforeDetach(
                save = { Result.failure(IllegalStateException("storage failure")) },
                detach = {
                    detached = true
                    Result.success(true)
                },
            ).getOrThrow()
        }

        assertFalse(detached)
        assertEquals("storage failure", result.exceptionOrNull()?.message)
    }

    @Test
    fun disabledCoreImportsPinsAndDetachesLegacyWithoutEnablingCore() = runBlocking {
        val store = MostUsedSettingsStore(MemoryPreferences())
        val pin = AppKey("example.app", "Main", 1L)
        store.setEnabled(false).getOrThrow()
        var detached = false

        val result = saveBeforeDetach(
            save = { store.importLegacy("official:instance", listOf(pin), 14, 5) },
            detach = {
                detached = true
                Result.success(true)
            },
        )

        assertEquals(true, result.getOrThrow())
        assertEquals(true, detached)
        assertEquals(false, store.currentSettings().enabled)
        assertEquals(listOf(pin), store.currentSettings().pinnedKeys)
        assertEquals(14, store.currentSettings().days)
        assertEquals(5, store.currentSettings().suggestedCount)
        assertEquals(setOf("official:instance"), store.currentSettings().importedLegacySources)
        store.importLegacy("official:instance", emptyList(), 7, 8).getOrThrow()
        assertEquals(listOf(pin), store.currentSettings().pinnedKeys)
        assertEquals(false, store.currentSettings().enabled)
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val mutable = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: StateFlow<Preferences> = mutable

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(mutable.value.toMutablePreferences())
            mutable.value = next
            return next
        }
    }
}
