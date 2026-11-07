package io.github.alexanderwilhelmsenberg.modular.appearance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAppearancePersistenceTest {
    @Test
    fun backgroundDimmingAndIconsSurviveNewStoreWithoutChangingTheme() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AppearanceSettingsStore(context)
        val priorSettings = store.currentSettings()
        val priorTheme = store.currentTheme()
        try {
            assertTrue(store.setBackground(HomeBackground.SYSTEM_WALLPAPER).isSuccess)
            assertTrue(store.setWallpaperDimming(100).isSuccess)
            assertTrue(store.setShowAppIcons(false).isSuccess)

            val restored = AppearanceSettingsStore(context)
            assertEquals(
                HomeAppearanceSettings(HomeBackground.SYSTEM_WALLPAPER, 80, false),
                restored.currentSettings(),
            )
            assertEquals(priorTheme, restored.currentTheme())
        } finally {
            store.setBackground(priorSettings.background)
            store.setWallpaperDimming(priorSettings.wallpaperDimming)
            store.setShowAppIcons(priorSettings.showAppIcons)
        }
    }
}
