package io.github.alexanderwilhelmsenberg.modular.mostused

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMostUsedHistoryStoreTest {
    private val main = AppKey("same.package", "Main", 1)
    private val otherActivity = AppKey("same.package", "Other", 1)
    private val otherProfile = AppKey("same.package", "Main", 2)

    @Test fun dailyCountsPersistByFullAppIdentityAndRecentLaunchesWeighMore() = runTest {
        val data = MemoryPreferences()
        var now = 0L
        val first = LocalMostUsedHistoryStore(data) { now }
        repeat(2) { first.recordLaunch(main).getOrThrow() }
        first.recordLaunch(otherActivity).getOrThrow()
        first.recordLaunch(otherProfile).getOrThrow()
        now = 7 * DAY_MILLIS
        val recreated = LocalMostUsedHistoryStore(data) { now }
        recreated.recordLaunch(main).getOrThrow()
        val scores = recreated.currentHistory().scores(8)
        assertEquals(2.0, scores[main]!!, 0.0001)
        assertEquals(0.5, scores[otherActivity]!!, 0.0001)
        assertEquals(0.5, scores[otherProfile]!!, 0.0001)
        assertEquals(mapOf(main to 1.0), recreated.currentHistory().scores(1))
    }

    @Test fun historyIsBoundedAndExpiredCountsAreIgnoredAndPrunedOnWrite() = runTest {
        val data = MemoryPreferences()
        var now = 0L
        val store = LocalMostUsedHistoryStore(data) { now }
        repeat(1_202) { store.recordLaunch(AppKey("pkg.$it", "Main", 1)).getOrThrow() }
        assertEquals(1_200, store.currentHistory().scores(30).size)
        now = 31 * DAY_MILLIS
        assertTrue(store.currentHistory().scores(30).isEmpty())
        store.recordLaunch(main).getOrThrow()
        assertEquals(mapOf(main to 1.0), store.currentHistory().scores(30))
    }

    @Test fun resetClearsHistoryAndCanRecoverCorruptHistory() = runTest {
        val data = MemoryPreferences()
        val store = LocalMostUsedHistoryStore(data) { 0L }
        store.recordLaunch(main).getOrThrow()
        store.reset().getOrThrow()
        assertTrue(LocalMostUsedHistoryStore(data) { 0L }.currentHistory().scores(7).isEmpty())

        val key = stringPreferencesKey("daily_launch_counts_v1")
        val broken = emptyPreferences().toMutablePreferences().apply { this[key] = "{broken" }
        val corruptedData = MemoryPreferences(broken)
        val corrupted = LocalMostUsedHistoryStore(corruptedData) { 0L }
        assertTrue(corrupted.recordLaunch(main).isFailure)
        assertEquals("{broken", corruptedData.data.value[key])
        corrupted.reset().getOrThrow()
        assertTrue(corrupted.currentHistory().scores(7).isEmpty())
        corrupted.recordLaunch(main).getOrThrow()
        assertEquals(1.0, corrupted.currentHistory().scores(7)[main]!!, 0.0001)
    }

    private class MemoryPreferences(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val mutable = MutableStateFlow(initial)
        override val data: StateFlow<Preferences> = mutable
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(mutable.value.toMutablePreferences())
            mutable.value = next
            return next
        }
    }

    private companion object { const val DAY_MILLIS = 86_400_000L }
}
