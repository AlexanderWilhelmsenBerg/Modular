package io.github.alexanderwilhelmsenberg.modular.mostused

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LegacyMostUsedMigrationTest {
    private val pin = AppKey("example.app", "Main", 1L)

    @Test fun contextDetachRunsOnlyAfterSuccessfulSettingsCommit() = runBlocking {
        val events = mutableListOf<String>()
        val result = saveBeforeDetach(
            save = { events += "settings committed"; Result.success(Unit) },
            detach = { events += "binding detached"; Result.success(true) },
        )
        assertEquals(listOf("settings committed", "binding detached"), events)
        assertTrue(result.getOrThrow())
    }

    @Test fun failedSettingsCommitLeavesBindingSelected() = runBlocking {
        var detached = false
        val result = runCatching {
            saveBeforeDetach(
                save = { Result.failure(IllegalStateException("storage failure")) },
                detach = { detached = true; Result.success(true) },
            ).getOrThrow()
        }
        assertFalse(detached)
        assertEquals("storage failure", result.exceptionOrNull()?.message)
    }

    @Test fun defaultOffImportsDataAndDetachesWithoutEnablingCore() = runBlocking {
        val store = MostUsedSettingsStore(MemoryPreferences())
        val result = saveBeforeDetach(
            save = { store.importLegacy("official:instance", listOf(pin), 14, 5) },
            detach = { Result.success(true) },
        )
        assertTrue(result.getOrThrow())
        assertFalse(store.currentSettings().enabled)
        assertEquals(listOf(pin), store.currentSettings().pinnedKeys)
        assertEquals(14, store.currentSettings().days)
        assertEquals(5, store.currentSettings().suggestedCount)
        assertEquals(setOf("official:instance"), store.currentSettings().importedLegacySources)
    }

    @Test fun alreadyEnabledCoreRemainsEnabledAfterImportAndDetach() = runBlocking {
        val store = MostUsedSettingsStore(MemoryPreferences())
        store.setEnabled(true).getOrThrow()
        assertTrue(saveBeforeDetach(
            save = { store.importLegacy("official:instance", listOf(pin), 14, 5) },
            detach = { Result.success(true) },
        ).getOrThrow())
        assertTrue(store.currentSettings().enabled)
        assertEquals(listOf(pin), store.currentSettings().pinnedKeys)
    }

    @Test fun staleDetachKeepsImportedDataAndRetryDoesNotResurrectRemovedPin() = runBlocking {
        val store = MostUsedSettingsStore(MemoryPreferences())
        val source = "official:instance"
        assertFalse(saveBeforeDetach(
            save = { store.importLegacy(source, listOf(pin), 14, 5) },
            detach = { Result.success(false) },
        ).getOrThrow())
        assertFalse(store.currentSettings().enabled)
        assertEquals(listOf(pin), store.currentSettings().pinnedKeys)

        store.setPinned(pin, false, emptySet()).getOrThrow()
        assertTrue(saveBeforeDetach(
            save = { store.importLegacy(source, listOf(pin), 14, 5) },
            detach = { Result.success(true) },
        ).getOrThrow())
        assertEquals(emptyList<AppKey>(), store.currentSettings().pinnedKeys)
        assertFalse(store.currentSettings().enabled)
        assertEquals(setOf(source), store.currentSettings().importedLegacySources)
    }

    @Test fun cancellationAfterCommitKeepsCoreOffAndImportedDataForRetry() = runBlocking {
        val store = MostUsedSettingsStore(MemoryPreferences())
        var detached = false
        try {
            saveBeforeDetach(
                save = {
                    store.importLegacy("official:instance", listOf(pin), 14, 5).getOrThrow()
                    throw CancellationException("canceled after commit")
                },
                detach = { detached = true; Result.success(true) },
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // A later attempt can detach using the import marker.
        }
        assertFalse(detached)
        assertFalse(store.currentSettings().enabled)
        assertEquals(listOf(pin), store.currentSettings().pinnedKeys)
        assertEquals(setOf("official:instance"), store.currentSettings().importedLegacySources)
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
