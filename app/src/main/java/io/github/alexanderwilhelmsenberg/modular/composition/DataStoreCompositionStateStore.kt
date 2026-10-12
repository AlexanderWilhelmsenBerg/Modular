package io.github.alexanderwilhelmsenberg.modular.composition

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.toPreferences
import androidx.datastore.preferences.PreferenceDataStoreFactory
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DataStoreCompositionStateStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) : CompositionStateStore {
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dataStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = {
            context.applicationContext.preferencesDataStoreFile(
                fileName,
            )
        },
    )

    private val stateKey =
        stringPreferencesKey("composition_state_json")

    private val _loadError = MutableStateFlow<String?>(null)

    override val loadError: StateFlow<String?> = _loadError

    override val state: StateFlow<CompositionState> =
        dataStore.data
            .map { preferences ->
                decodeForObservation(preferences[stateKey])
            }
            .catch { throwable ->
                if (throwable is IOException) {
                    _loadError.value =
                        throwable.message ?: "Composition store I/O failure"
                    emit(CompositionState())
                } else {
                    throw throwable
                }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = CompositionState(),
            )

    override suspend fun update(
        transform: (CompositionState) -> CompositionState,
    ): CompositionState {
        var updated: CompositionState? = null

        dataStore.edit { preferences ->
            val current = CompositionStateCodec.decode(
                preferences[stateKey],
            )
            val next = transform(current)

            preferences[stateKey] =
                CompositionStateCodec.encode(next)
            updated = next
        }

        _loadError.value = null
        return requireNotNull(updated)
    }

    override fun close() {
        scope.cancel()
    }

    private fun decodeForObservation(
        raw: String?,
    ): CompositionState =
        runCatching {
            CompositionStateCodec.decode(raw)
        }.fold(
            onSuccess = { state ->
                _loadError.value = null
                state
            },
            onFailure = { throwable ->
                _loadError.value =
                    throwable.message
                        ?: throwable::class.java.simpleName
                CompositionState()
            },
        )

    companion object {
        const val DEFAULT_FILE_NAME =
            "modular-composition.preferences_pb"
    }
}
