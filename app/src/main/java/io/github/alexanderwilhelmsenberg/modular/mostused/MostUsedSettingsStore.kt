package io.github.alexanderwilhelmsenberg.modular.mostused

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
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

private val Context.mostUsedDataStore by preferencesDataStore(name = "core_most_used")
private val settingsKey = stringPreferencesKey("settings_json_v1")
private val json = Json { ignoreUnknownKeys = true }

data class MostUsedSettings(
    val enabled: Boolean = false,
    val days: Int = 7,
    val suggestedCount: Int = 8,
    val pinnedKeys: List<AppKey> = emptyList(),
    val importedLegacySources: Set<String> = emptySet(),
    val userConfigured: Boolean = false,
)

@Serializable
private data class SavedAppKey(val packageName: String, val activityName: String, val profileSerial: Long) {
    fun toAppKey() = AppKey(packageName, activityName, profileSerial)
    companion object {
        fun from(key: AppKey) = SavedAppKey(key.packageName, key.activityName, key.profileSerial)
    }
}

@Serializable
private data class SavedSettings(
    val enabled: Boolean = false,
    val days: Int = 7,
    val suggestedCount: Int = 8,
    val pins: List<SavedAppKey> = emptyList(),
    val enabledTouched: Boolean = false,
    val daysTouched: Boolean = false,
    val countTouched: Boolean = false,
    val pinsTouched: Boolean = false,
    val importedSources: Set<String> = emptySet(),
) {
    fun public() = MostUsedSettings(
        enabled, days, suggestedCount, pins.map { it.toAppKey() }, importedSources,
        enabledTouched || daysTouched || countTouched || pinsTouched,
    )
}

/** Core-owned preferences. Each mutation is one DataStore transaction. */
class MostUsedSettingsStore internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.mostUsedDataStore)

    private val mutableError = MutableStateFlow<String?>(null)
    private val lastGood = MutableStateFlow(MostUsedSettings())
    val error: StateFlow<String?> = mutableError

    val settings: Flow<MostUsedSettings> = dataStore.data.map { preferences ->
        decode(preferences[settingsKey]).public().also { decoded ->
            lastGood.value = decoded
            mutableError.value = null
        }
    }.retryWhen { failure, _ ->
        if (failure is CancellationException) return@retryWhen false
        mutableError.value = "Could not load Most Used settings."
        emit(lastGood.value)
        delay(1_000)
        true
    }

    suspend fun currentSettings(): MostUsedSettings = settings.first()

    suspend fun setEnabled(enabled: Boolean): Result<Unit> = write {
        it.copy(enabled = enabled, enabledTouched = true)
    }

    suspend fun setDays(days: Int): Result<Unit> = write {
        require(days in 1..30) { "Days must be between 1 and 30" }
        it.copy(days = days, daysTouched = true)
    }

    suspend fun setSuggestedCount(count: Int): Result<Unit> = write {
        require(count in 0..20) { "Suggestion count must be between 0 and 20" }
        it.copy(suggestedCount = count, countTouched = true)
    }

    /** Adding requires membership in the current raw catalog; removing an absent app is allowed. */
    suspend fun setPinned(key: AppKey, pinned: Boolean, availableKeys: Set<AppKey>): Result<Unit> = write { current ->
        require(validKey(key)) { "Invalid app key" }
        val saved = SavedAppKey.from(key)
        if (pinned) {
            require(key in availableKeys) { "App is not in the current catalog" }
            if (saved in current.pins) current else {
                require(current.pins.size < MAX_PINS) { "Pin limit reached" }
                current.copy(pins = current.pins + saved, pinsTouched = true)
            }
        } else {
            current.copy(pins = current.pins - saved, pinsTouched = true)
        }
    }

    /** The supplied order must contain exactly the saved pins, including unavailable ones. */
    suspend fun reorderPins(keys: List<AppKey>): Result<Unit> = write { current ->
        require(keys.size <= MAX_PINS && keys.all(::validKey)) { "Invalid pin order" }
        val order = keys.map(SavedAppKey::from)
        require(order.size == current.pins.size && order.toSet() == current.pins.toSet()) {
            "Pin order must include every saved pin exactly once"
        }
        current.copy(pins = order, pinsTouched = true)
    }

    /** Imports once per source. Existing Core pins keep their order; no pin is silently dropped. */
    suspend fun importLegacy(
        sourceId: String,
        pins: List<AppKey>,
        days: Int,
        suggestedCount: Int,
    ): Result<Unit> = write { current ->
        require(sourceId.isNotBlank()) { "Migration source is required" }
        require(days in 1..30 && suggestedCount in 0..20) { "Invalid legacy settings" }
        require(pins.size <= MAX_PINS && pins.all(::validKey) && pins.distinct().size == pins.size) {
            "Invalid legacy pins"
        }
        if (sourceId in current.importedSources) current else {
            val merged = current.pins + pins.map(SavedAppKey::from).filterNot { it in current.pins }
            require(merged.size <= MAX_PINS) { "Migration would exceed the pin limit" }
            current.copy(
                days = if (current.daysTouched) current.days else days,
                suggestedCount = if (current.countTouched) current.suggestedCount else suggestedCount,
                pins = merged,
                importedSources = current.importedSources + sourceId,
            )
        }
    }

    private suspend fun write(change: (SavedSettings) -> SavedSettings): Result<Unit> = try {
        dataStore.edit { preferences ->
            preferences[settingsKey] = json.encodeToString(SavedSettings.serializer(), change(decode(preferences[settingsKey])))
        }
        mutableError.value = null
        Result.success(Unit)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        mutableError.value = "Could not save Most Used settings."
        Result.failure(failure)
    }

    private fun decode(raw: String?): SavedSettings = if (raw == null) SavedSettings()
    else json.decodeFromString(SavedSettings.serializer(), raw).also { saved ->
        require(saved.days in 1..30 && saved.suggestedCount in 0..20) { "Invalid saved Most Used settings" }
        require(saved.pins.size <= MAX_PINS && saved.pins.all { validKey(it.toAppKey()) } &&
            saved.pins.distinct().size == saved.pins.size) { "Invalid saved Most Used pins" }
    }

    companion object { const val MAX_PINS = 50 }
}

private fun validKey(key: AppKey): Boolean =
    key.packageName.isNotBlank() && key.packageName.length <= 255 &&
        key.activityName.isNotBlank() && key.activityName.length <= 512 &&
        key.profileSerial >= 0L
