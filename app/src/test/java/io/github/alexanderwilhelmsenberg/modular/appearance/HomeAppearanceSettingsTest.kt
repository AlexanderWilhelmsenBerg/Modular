package io.github.alexanderwilhelmsenberg.modular.appearance

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeAppearanceSettingsTest {
    @Test
    fun freshInstallKeepsThemeBackgroundAndShowsIcons() {
        assertEquals(HomeBackground.THEME, HomeAppearanceSettings().background)
        assertEquals(35, HomeAppearanceSettings().wallpaperDimming)
        assertEquals(true, HomeAppearanceSettings().showAppIcons)
    }

    @Test
    fun invalidStoredBackgroundAndDimmingFallBackToSafeValues() {
        assertEquals(HomeBackground.THEME, HomeBackground.fromId(null))
        assertEquals(HomeBackground.THEME, HomeBackground.fromId("removed:wallpaper-provider"))
        assertEquals(HomeBackground.SYSTEM_WALLPAPER, HomeBackground.fromId("system_wallpaper"))
        assertEquals(0, clampWallpaperDimming(-10))
        assertEquals(80, clampWallpaperDimming(300))
    }
}
