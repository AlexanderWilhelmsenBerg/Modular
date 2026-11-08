package io.github.alexanderwilhelmsenberg.modular.mostused

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.organization.HomePinRef
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

internal val Context.coreMostUsedDataStore by preferencesDataStore(name = "core_most_used")
internal val mostUsedSettingsKey = stringPreferencesKey("settings_json_v1")
internal val mostUsedJson = Json { ignoreUnknownKeys = true }

data class MostUsedSettings(
    val enabled: Boolean = false,
    val days: Int = 7,
    val suggestedCount: Int = 8,
    val pinnedKeys: List<AppKey> = emptyList(),
    val pinRefs: List<HomePinRef> = pinnedKeys.map(HomePinRef::App),
    /** Legacy app pins are projected immediately; migration is completed on the next edit. */
    val pinsMigrationComplete: Boolean = false,
    val importedLegacySources: Set<String> = emptySet(),
    val userConfigured: Boolean = false,
    val source: MostUsedSource = MostUsedSource.LOCAL,
)

/** Android-wide history is an explicit opt-in; existing saved settings remain local. */
enum class MostUsedSource { LOCAL, ANDROID }

@Serializable
internal data class SavedAppKey(val packageName: String, val activityName: String, val profileSerial: Long) {
    fun toAppKey() = AppKey(packageName, activityName, profileSerial)
    companion object {
        fun from(key: AppKey) = SavedAppKey(key.packageName, key.activityName, key.profileSerial)
    }
}

@Serializable
internal data class SavedPinRef(val kind: String, val app: SavedAppKey? = null, val folderId: String? = null) {
    fun public(): HomePinRef = when (kind) {
        "app" -> HomePinRef.App(requireNotNull(app).toAppKey())
        "folder" -> HomePinRef.Folder(requireNotNull(folderId))
        else -> error("Unknown pin type")
    }
    companion object {
        fun from(ref: HomePinRef): SavedPinRef = when (ref) {
            is HomePinRef.App -> SavedPinRef("app", SavedAppKey.from(ref.key))
            is HomePinRef.Folder -> SavedPinRef("folder", folderId = ref.id)
        }
    }
}

@Serializable
internal data class SavedSettings(
    val enabled: Boolean = false,
    val days: Int = 7,
    val suggestedCount: Int = 8,
    val pins: List<SavedAppKey> = emptyList(),
    /** Null is the v1 app-only payload. Once set, this is the sole pin order. */
    val pinRefs: List<SavedPinRef>? = null,
    val pinSchemaVersion: Int = 1,
    val enabledTouched: Boolean = false,
    val daysTouched: Boolean = false,
    val countTouched: Boolean = false,
    val pinsTouched: Boolean = false,
    val importedSources: Set<String> = emptySet(),
    val source: MostUsedSource = MostUsedSource.LOCAL,
    val sourceTouched: Boolean = false,
) {
    fun effectivePins(): List<SavedPinRef> = pinRefs ?: pins.map { SavedPinRef("app", it) }
    fun public() = MostUsedSettings(
        enabled, days, suggestedCount,
        effectivePins().mapNotNull { (it.public() as? HomePinRef.App)?.key },
        effectivePins().map { it.public() }, pinSchemaVersion == 2, importedSources,
        enabledTouched || daysTouched || countTouched || pinsTouched || sourceTouched,
        source,
    )
}

/** Core-owned preferences. Each mutation is one DataStore transaction. */
class MostUsedSettingsStore internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.coreMostUsedDataStore)

    private val mutableError = MutableStateFlow<String?>(null)
    private val lastGood = MutableStateFlow(MostUsedSettings())
    val error: StateFlow<String?> = mutableError

    val settings: Flow<MostUsedSettings> = dataStore.data.map { preferences ->
        decodeMostUsedSettings(preferences[mostUsedSettingsKey]).public().also { decoded ->
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

    suspend fun setSource(source: MostUsedSource): Result<Unit> = write {
        it.copy(source = source, sourceTouched = true)
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
        val refs = current.effectivePins()
        if (pinned) {
            require(key in availableKeys) { "App is not in the current catalog" }
            val ref = SavedPinRef("app", saved)
            if (ref in refs) current else {
                require(refs.size < MAX_PINS) { "Pin limit reached" }
                current.copy(pinRefs = refs + ref, pinsTouched = true)
            }
        } else {
            current.copy(pinRefs = refs - SavedPinRef("app", saved), pinsTouched = true)
        }
    }

    /** The supplied order must contain exactly the saved pins, including unavailable ones. */
    suspend fun reorderPins(keys: List<AppKey>): Result<Unit> = write { current ->
        require(keys.size <= MAX_PINS && keys.all(::validKey)) { "Invalid pin order" }
        val order = keys.map(SavedAppKey::from)
        val currentApps = current.effectivePins().mapNotNull { if (it.kind == "app") it.app else null }
        require(order.size == currentApps.size && order.toSet() == currentApps.toSet()) {
            "Pin order must include every saved pin exactly once"
        }
        val iter = order.iterator()
        current.copy(pinRefs = current.effectivePins().map { ref ->
            if (ref.kind == "app") SavedPinRef("app", iter.next()) else ref
        }, pinsTouched = true)
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
            val refs = current.effectivePins()
            val incoming = pins.map { SavedPinRef("app", SavedAppKey.from(it)) }
            val merged = refs + incoming.filterNot { it in refs }
            require(merged.size <= MAX_PINS) { "Migration would exceed the pin limit" }
            current.copy(
                days = if (current.daysTouched) current.days else days,
                suggestedCount = if (current.countTouched) current.suggestedCount else suggestedCount,
                pinRefs = merged,
                importedSources = current.importedSources + sourceId,
            )
        }
    }

    private suspend fun write(change: (SavedSettings) -> SavedSettings): Result<Unit> = try {
        dataStore.edit { preferences ->
            val next = change(decodeMostUsedSettings(preferences[mostUsedSettingsKey]))
            preferences[mostUsedSettingsKey] = encodeMostUsedSettings(
                if (next.pinRefs == null) next else next.copy(pinSchemaVersion = 2),
            )
        }
        mutableError.value = null
        Result.success(Unit)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        mutableError.value = "Could not save Most Used settings."
        Result.failure(failure)
    }

    companion object { const val MAX_PINS = 50 }
}

internal fun encodeMostUsedSettings(value: SavedSettings): String =
    mostUsedJson.encodeToString(SavedSettings.serializer(), value)

internal fun decodeMostUsedSettings(raw: String?): SavedSettings = if (raw == null) SavedSettings()
else mostUsedJson.decodeFromString(SavedSettings.serializer(), raw).also { saved ->
    require(saved.days in 1..30 && saved.suggestedCount in 0..20) { "Invalid saved Most Used settings" }
    require((saved.pinSchemaVersion == 1 && saved.pinRefs == null) ||
        (saved.pinSchemaVersion == 2 && saved.pinRefs != null)) { "Invalid pin schema version" }
    val refs = saved.effectivePins()
    require(refs.size <= MostUsedSettingsStore.MAX_PINS && refs.distinct().size == refs.size) {
        "Invalid saved Most Used pins"
    }
    require(refs.all { ref -> when (val pin = ref.public()) {
        is HomePinRef.App -> validKey(pin.key)
        is HomePinRef.Folder -> pin.id.isNotBlank() && pin.id.length <= 128
    } }) { "Invalid saved Most Used pin reference" }
}

private fun validKey(key: AppKey): Boolean =
    key.packageName.isNotBlank() && key.packageName.length <= 255 &&
        key.activityName.isNotBlank() && key.activityName.length <= 512 &&
        key.profileSerial >= 0L
