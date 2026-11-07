package io.github.alexanderwilhelmsenberg.modular.mostused

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.localMostUsedDataStore by preferencesDataStore(name = "core_most_used_local_history")
private val historyKey = stringPreferencesKey("daily_launch_counts_v1")
private val historyJson = Json { ignoreUnknownKeys = true }
private const val DAY_MILLIS = 86_400_000L
private const val RETAIN_DAYS = 30
private const val MAX_BUCKETS = 1_200

@Serializable
private data class LaunchBucket(
    val packageName: String,
    val activityName: String,
    val profileSerial: Long,
    val epochDay: Long,
    val count: Int,
) {
    val key get() = AppKey(packageName, activityName, profileSerial)
}

@Serializable
private data class SavedHistory(val buckets: List<LaunchBucket> = emptyList())

/** Aggregate daily launch counts only; no individual events or timestamps are retained. */
class LocalMostUsedHistory internal constructor(
    private val counts: Map<Pair<AppKey, Long>, Int>,
    private val clockMillis: () -> Long,
) {
    /** Seven-day half-life favors recent launches within the selected day window. */
    fun scores(days: Int): Map<AppKey, Double> {
        require(days in 1..RETAIN_DAYS)
        val today = Math.floorDiv(clockMillis(), DAY_MILLIS)
        val result = HashMap<AppKey, Double>()
        counts.forEach { (identity, count) ->
            val age = today - identity.second
            if (age >= 0L && age < days.toLong() && count > 0) {
                val weight = 2.0.pow(-age.toDouble() / 7.0)
                result[identity.first] = (result[identity.first] ?: 0.0) + count * weight
            }
        }
        return result
    }

    companion object {
        internal fun empty(clockMillis: () -> Long) = LocalMostUsedHistory(emptyMap(), clockMillis)
    }
}

/** Private, bounded history distinct from pins and settings. */
class LocalMostUsedHistoryStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(context.applicationContext.localMostUsedDataStore)

    private val mutableError = MutableStateFlow<String?>(null)
    private val lastGood = MutableStateFlow(LocalMostUsedHistory.empty(clockMillis))
    val error: StateFlow<String?> = mutableError

    val history: Flow<LocalMostUsedHistory> = dataStore.data.map { preferences ->
        decode(preferences[historyKey]).toPublic(today()).also { decoded ->
            lastGood.value = decoded
            mutableError.value = null
        }
    }.retryWhen { failure, _ ->
        if (failure is CancellationException) return@retryWhen false
        mutableError.value = "Could not load learned app usage."
        emit(lastGood.value)
        delay(1_000)
        true
    }

    suspend fun currentHistory(): LocalMostUsedHistory = history.first()

    suspend fun recordLaunch(key: AppKey): Result<Unit> = write { current, today ->
        require(validKey(key))
        val retained = current.buckets.filter { it.epochDay in (today - RETAIN_DAYS + 1)..today }
        val match = retained.firstOrNull { it.key == key && it.epochDay == today }
        val next = retained.filterNot { it.key == key && it.epochDay == today } + LaunchBucket(
            key.packageName, key.activityName, key.profileSerial, today,
            (match?.count ?: 0).let { if (it == Int.MAX_VALUE) it else it + 1 },
        )
        SavedHistory(next.sortedWith(bucketOrder).take(MAX_BUCKETS))
    }

    /** Erases learned counts while preserving Most Used settings and pins. */
    suspend fun reset(): Result<Unit> = try {
        dataStore.edit { preferences ->
            preferences[historyKey] = historyJson.encodeToString(SavedHistory.serializer(), SavedHistory())
        }
        mutableError.value = null
        Result.success(Unit)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        mutableError.value = "Could not reset learned app usage."
        Result.failure(failure)
    }

    private suspend fun write(change: (SavedHistory, Long) -> SavedHistory): Result<Unit> = try {
        dataStore.edit { preferences ->
            val today = today()
            preferences[historyKey] = historyJson.encodeToString(
                SavedHistory.serializer(), change(decode(preferences[historyKey]), today),
            )
        }
        mutableError.value = null
        Result.success(Unit)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        mutableError.value = "Could not save learned app usage."
        Result.failure(failure)
    }

    private fun decode(raw: String?): SavedHistory = if (raw == null) SavedHistory()
    else historyJson.decodeFromString(SavedHistory.serializer(), raw).also { saved ->
        require(saved.buckets.size <= MAX_BUCKETS && saved.buckets.all { bucket ->
            validKey(bucket.key) && bucket.count > 0
        }) { "Invalid learned app usage" }
        require(saved.buckets.map { it.key to it.epochDay }.distinct().size == saved.buckets.size) {
            "Duplicate learned app usage"
        }
    }

    private fun SavedHistory.toPublic(today: Long): LocalMostUsedHistory = LocalMostUsedHistory(
        buckets.filter { it.epochDay in (today - RETAIN_DAYS + 1)..today }
            .associate { (it.key to it.epochDay) to it.count }, clockMillis,
    )

    private fun today(): Long = Math.floorDiv(clockMillis(), DAY_MILLIS)

    private fun validKey(key: AppKey): Boolean =
        key.packageName.isNotBlank() && key.packageName.length <= 255 &&
            key.activityName.isNotBlank() && key.activityName.length <= 512 &&
            key.profileSerial >= 0L

    private companion object {
        val bucketOrder = compareByDescending<LaunchBucket> { it.epochDay }
            .thenByDescending { it.count }
            .thenBy { it.packageName }
            .thenBy { it.activityName }
            .thenBy { it.profileSerial }
    }
}
