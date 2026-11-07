package io.github.alexanderwilhelmsenberg.modular.appearance

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

enum class HomeBackground(val id: String, val label: String) {
    THEME("theme", "Theme"),
    SYSTEM_WALLPAPER("system_wallpaper", "System wallpaper"),
    ;

    companion object {
        fun fromId(id: String?): HomeBackground = entries.firstOrNull { it.id == id } ?: THEME
    }
}

const val DEFAULT_WALLPAPER_DIMMING = 35
const val MAX_WALLPAPER_DIMMING = 80

data class HomeAppearanceSettings(
    val background: HomeBackground = HomeBackground.THEME,
    val wallpaperDimming: Int = DEFAULT_WALLPAPER_DIMMING,
    val showAppIcons: Boolean = true,
)

fun clampWallpaperDimming(value: Int): Int = value.coerceIn(0, MAX_WALLPAPER_DIMMING)

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance")
private val themeIdKey = stringPreferencesKey("theme_id")
private val homeBackgroundKey = stringPreferencesKey("home_background")
private val wallpaperDimmingKey = intPreferencesKey("wallpaper_dimming")
private val showAppIconsKey = booleanPreferencesKey("show_app_icons")

/** One DataStore per application process, even if activities are recreated. */
class AppearanceSettingsStore(context: Context) {
    private val dataStore = context.applicationContext.appearanceDataStore
    private val mutableError = MutableStateFlow<String?>(null)
    private val lastGoodTheme = MutableStateFlow(AppearanceTheme.LIGHT)
    private val lastGoodSettings = MutableStateFlow(HomeAppearanceSettings())

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

    val settings: Flow<HomeAppearanceSettings> = dataStore.data
        .map { preferences ->
            mutableError.value = null
            HomeAppearanceSettings(
                background = HomeBackground.fromId(preferences[homeBackgroundKey]),
                wallpaperDimming = clampWallpaperDimming(
                    preferences[wallpaperDimmingKey] ?: DEFAULT_WALLPAPER_DIMMING,
                ),
                showAppIcons = preferences[showAppIconsKey] ?: true,
            ).also { lastGoodSettings.value = it }
        }
        .retryWhen { failure, _ ->
            if (failure is CancellationException) return@retryWhen false
            mutableError.value = "Could not load appearance: ${failure.message ?: failure.javaClass.simpleName}"
            emit(lastGoodSettings.value)
            delay(1_000)
            true
        }

    val background: Flow<HomeBackground> = settings.map { it.background }
    val wallpaperDimming: Flow<Int> = settings.map { it.wallpaperDimming }
    val showAppIcons: Flow<Boolean> = settings.map { it.showAppIcons }

    /** Useful for an initial render that matches the saved theme without a color flash. */
    suspend fun currentTheme(): AppearanceTheme = theme.first()

    suspend fun currentSettings(): HomeAppearanceSettings = settings.first()

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

    suspend fun setBackground(background: HomeBackground): Result<Unit> = write {
        it[homeBackgroundKey] = background.id
    }

    suspend fun setWallpaperDimming(percent: Int): Result<Unit> = write {
        it[wallpaperDimmingKey] = clampWallpaperDimming(percent)
    }

    suspend fun setShowAppIcons(show: Boolean): Result<Unit> = write {
        it[showAppIconsKey] = show
    }

    private suspend fun write(update: (MutablePreferences) -> Unit): Result<Unit> = try {
        dataStore.edit { preferences -> update(preferences) }
        mutableError.value = null
        Result.success(Unit)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        mutableError.value = "Could not save appearance: ${failure.message ?: failure.javaClass.simpleName}"
        Result.failure(failure)
    }
}
