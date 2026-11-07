package io.github.alexanderwilhelmsenberg.modular.apps

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.appCustomizationDataStore by preferencesDataStore(name = "app_customizations")
private val customizationsKey = stringPreferencesKey("app_customizations_json_v1")
private val codec = Json { ignoreUnknownKeys = true }

@Serializable
private data class PersistedCustomizations(
    val labels: Map<String, String> = emptyMap(),
    val hidden: Set<String> = emptySet(),
)

/** One process-wide DataStore; errors are exposed so UI can report failed reads and writes. */
class AppCustomizationStore internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.appCustomizationDataStore)
    private val mutableError = MutableStateFlow<String?>(null)
    private val lastGood = MutableStateFlow(AppCustomizations())

    val error: StateFlow<String?> = mutableError

    val customizations: Flow<AppCustomizations> = dataStore.data
        .map { preferences ->
            decode(preferences[customizationsKey]).also {
                lastGood.value = it
                mutableError.value = null
            }
        }
        .retryWhen { failure, _ ->
            if (failure is CancellationException) return@retryWhen false
            mutableError.value = "Could not load app customizations: ${failure.message ?: failure.javaClass.simpleName}"
            emit(lastGood.value)
            delay(1_000)
            true
        }

    suspend fun currentCustomizations(): AppCustomizations = customizations.first()

    suspend fun setLabel(key: AppKey, label: String?): Result<Unit> = write { current ->
        val normalized = normalizeCustomAppLabel(label)
        val labels = current.labels.toMutableMap()
        if (normalized == null) labels.remove(key.stableId) else labels[key.stableId] = normalized
        current.copy(labels = labels)
    }

    suspend fun setHidden(key: AppKey, hidden: Boolean): Result<Unit> = write { current ->
        val ids = current.hidden.toMutableSet()
        if (hidden) ids.add(key.stableId) else ids.remove(key.stableId)
        current.copy(hidden = ids)
    }

    private suspend fun write(transform: (AppCustomizations) -> AppCustomizations): Result<Unit> = try {
        dataStore.edit { preferences ->
            val next = transform(decode(preferences[customizationsKey]))
            preferences[customizationsKey] = encode(next)
        }
        mutableError.value = null
        Result.success(Unit)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        mutableError.value = "Could not save app customizations: ${failure.message ?: failure.javaClass.simpleName}"
        Result.failure(failure)
    }

    private fun decode(raw: String?): AppCustomizations {
        if (raw == null) return AppCustomizations()
        val saved = codec.decodeFromString(PersistedCustomizations.serializer(), raw)
        return AppCustomizations(saved.labels, saved.hidden)
    }

    private fun encode(value: AppCustomizations): String = codec.encodeToString(
        PersistedCustomizations.serializer(),
        PersistedCustomizations(value.labels, value.hidden),
    )
}
