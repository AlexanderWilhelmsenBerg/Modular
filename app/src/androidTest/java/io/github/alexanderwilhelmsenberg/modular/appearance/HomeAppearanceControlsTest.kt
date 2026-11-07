package io.github.alexanderwilhelmsenberg.modular.appearance

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeAppearanceControlsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun wallpaperAndIconControlsUpdateWithoutChangingTheme() {
        var settings by mutableStateOf(HomeAppearanceSettings())
        composeRule.setContent {
            ModularTheme(AppearanceTheme.AMOLED) {
                AppearanceHomeControls(
                    settings = settings,
                    onBackgroundSelected = { settings = settings.copy(background = it) },
                    onWallpaperDimmingSelected = { settings = settings.copy(wallpaperDimming = it) },
                    onShowAppIconsChanged = { settings = settings.copy(showAppIcons = it) },
                )
            }
        }

        composeRule.onNodeWithTag("home-background-theme").assertIsSelected()
        composeRule.onNodeWithTag("show-app-icons").assertIsOn().performClick().assertIsOff()
        composeRule.onNodeWithTag("home-background-system_wallpaper")
            .performClick().assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(HomeAppearanceSettings(HomeBackground.SYSTEM_WALLPAPER, 35, false), settings)
        }
    }
}
