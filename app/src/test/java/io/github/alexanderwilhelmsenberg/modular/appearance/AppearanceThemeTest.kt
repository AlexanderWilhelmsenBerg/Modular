package io.github.alexanderwilhelmsenberg.modular.appearance

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceThemeTest {
    @Test
    fun stableBuiltInIdsAndUnknownFallback() {
        assertEquals(AppearanceTheme.LIGHT, AppearanceTheme.fromId("light"))
        assertEquals(AppearanceTheme.DARK, AppearanceTheme.fromId("dark"))
        assertEquals(AppearanceTheme.AMOLED, AppearanceTheme.fromId("amoled"))
        assertEquals(AppearanceTheme.LIGHT, AppearanceTheme.fromId("removed:add-on-theme"))
        assertEquals(AppearanceTheme.LIGHT, AppearanceTheme.fromId(null))
    }
}
