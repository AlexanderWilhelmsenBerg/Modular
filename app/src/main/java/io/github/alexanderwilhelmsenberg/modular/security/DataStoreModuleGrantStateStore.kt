package io.github.alexanderwilhelmsenberg.modular.security

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class DataStoreModuleGrantStateStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) : ModuleGrantStateStore {
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
        stringPreferencesKey("module_grant_state_json")

    private val _state =
        MutableStateFlow(ModuleGrantState())
    override val state: StateFlow<ModuleGrantState> =
        _state.asStateFlow()

    private val _loadError =
        MutableStateFlow<String?>(null)
    override val loadError: StateFlow<String?> =
        _loadError.asStateFlow()

    init {
        scope.launch {
            dataStore.data
                .catch { throwable ->
                    if (throwable is IOException) {
                        _loadError.value =
                            throwable.message
                                ?: "Module-grant store I/O failure"
                        emit(
                            androidx.datastore.preferences.core
                                .emptyPreferences(),
                        )
                    } else {
                        throw throwable
                    }
                }
                .collect { preferences ->
                    runCatching {
                        ModuleGrantStateCodec.decode(
                            preferences[stateKey],
                        )
                    }.fold(
                        onSuccess = { decoded ->
                            _loadError.value = null
                            _state.value = decoded
                        },
                        onFailure = { throwable ->
                            _loadError.value =
                                throwable.message
                                    ?: throwable::class.java
                                        .simpleName
                            _state.value =
                                ModuleGrantState()
                        },
                    )
                }
        }
    }

    override suspend fun update(
        transform: (ModuleGrantState) -> ModuleGrantState,
    ): ModuleGrantState {
        var updated: ModuleGrantState? = null

        dataStore.edit { preferences ->
            val current = ModuleGrantStateCodec.decode(
                preferences[stateKey],
            )
            val next = normalize(
                transform(current),
            )

            preferences[stateKey] =
                ModuleGrantStateCodec.encode(next)
            updated = next
        }

        val result = requireNotNull(updated)
        _loadError.value = null
        _state.value = result
        return result
    }

    override fun close() {
        scope.cancel()
    }

    private fun normalize(
        state: ModuleGrantState,
    ): ModuleGrantState =
        state.copy(
            records = state.records
                .map { record ->
                    record.copy(
                        grantedCapabilities =
                            record.grantedCapabilities
                                .toSet(),
                    )
                }
                .sortedBy { record ->
                    record.identity.key
                },
        )

    companion object {
        const val DEFAULT_FILE_NAME =
            "modular-module-grants"
    }
}
