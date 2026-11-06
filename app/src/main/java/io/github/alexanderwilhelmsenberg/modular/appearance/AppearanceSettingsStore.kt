package io.github.alexanderwilhelmsenberg.modular.appearance

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

/** Stable Core theme IDs. Unknown IDs resolve to a usable built-in theme. */
enum class AppearanceTheme(val id: String, val label: String) {
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    AMOLED("amoled", "AMOLED"),
    ;

    companion object {
        fun fromId(id: String?): AppearanceTheme = entries.firstOrNull { it.id == id } ?: LIGHT
    }
}

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance")
private val themeIdKey = stringPreferencesKey("theme_id")

/** One DataStore per application process, even if activities are recreated. */
class AppearanceSettingsStore(context: Context) {
    private val dataStore = context.applicationContext.appearanceDataStore
    private val mutableError = MutableStateFlow<String?>(null)
    private val lastGoodTheme = MutableStateFlow(AppearanceTheme.LIGHT)

    val error: StateFlow<String?> = mutableError

    val theme: Flow<AppearanceTheme> = dataStore.data
        .map { preferences ->
            mutableError.value = null
            AppearanceTheme.fromId(preferences[themeIdKey]).also { lastGoodTheme.value = it }
        }
        .retryWhen { failure, _ ->
            if (failure is CancellationException) return@retryWhen false
            mutableError.value = "Could not load appearance: ${failure.message ?: failure.javaClass.simpleName}"
            emit(lastGoodTheme.value)
            delay(1_000)
            true
        }

    /** Useful for an initial render that matches the saved theme without a color flash. */
    suspend fun currentTheme(): AppearanceTheme = theme.first()

    /** A failed write leaves the previous selected theme intact and reports the failure. */
    suspend fun setTheme(theme: AppearanceTheme): Result<Unit> = try {
        dataStore.edit { preferences -> preferences[themeIdKey] = theme.id }
        mutableError.value = null
        Result.success(Unit)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        mutableError.value = "Could not save appearance: ${failure.message ?: failure.javaClass.simpleName}"
        Result.failure(failure)
    }
}
