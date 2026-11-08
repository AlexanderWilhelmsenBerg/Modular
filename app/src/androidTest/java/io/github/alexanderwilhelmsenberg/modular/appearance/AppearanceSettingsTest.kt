package io.github.alexanderwilhelmsenberg.modular.appearance

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.alexanderwilhelmsenberg.modular.ui.AppearanceSelector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppearanceSettingsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun radioRowsUpdateSelection() {
        var selected by mutableStateOf(AppearanceTheme.LIGHT)
        composeRule.setContent {
            ModularTheme(selected) {
                AppearanceSelector(selected, error = null, onSelect = { selected = it })
            }
        }

        composeRule.onNodeWithTag("appearance-light").assertIsSelected()
        composeRule.onNodeWithTag("appearance-amoled")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performClick()
            .assertIsSelected()
        composeRule.runOnIdle { assertEquals(AppearanceTheme.AMOLED, selected) }
        val rowHeight = composeRule.onNodeWithTag("appearance-amoled").fetchSemanticsNode().boundsInRoot.height
        assertTrue(rowHeight >= with(composeRule.density) { 48.dp.toPx() })
    }

    @Test
    fun amoledLargeSurfacesStayBlack() {
        lateinit var colors: ColorScheme
        composeRule.setContent { ModularTheme(AppearanceTheme.AMOLED) { colors = MaterialTheme.colorScheme } }
        composeRule.runOnIdle {
            assertEquals(Color.Black, colors.background)
            assertEquals(Color.Black, colors.surface)
            assertEquals(Color.Black, colors.surfaceContainer)
            assertEquals(Color.Black, colors.surfaceContainerHigh)
            assertEquals(Color.Transparent, colors.surfaceTint)
        }
    }

    @Test
    fun builtInThemesKeepTextAndControlContrast() {
        var selected by mutableStateOf(AppearanceTheme.LIGHT)
        lateinit var colors: ColorScheme
        var inheritedTextColor = Color.Unspecified
        composeRule.setContent { ModularTheme(selected) {
            colors = MaterialTheme.colorScheme
            inheritedTextColor = LocalContentColor.current
        } }

        AppearanceTheme.entries.forEach { theme ->
            composeRule.runOnIdle { selected = theme }
            composeRule.runOnIdle {
                assertEquals("Flat settings inherit the theme's text color", colors.onBackground, inheritedTextColor)
                listOf(
                    "primary" to contrast(colors.primary, colors.onPrimary),
                    "primary container" to contrast(colors.primaryContainer, colors.onPrimaryContainer),
                    "background" to contrast(colors.background, colors.onBackground),
                    "surface" to contrast(colors.surface, colors.onSurface),
                    "surface variant" to contrast(colors.surfaceVariant, colors.onSurfaceVariant),
                ).forEach { (name, ratio) ->
                    assertTrue("$theme $name text contrast was $ratio", ratio >= 4.5f)
                }
                assertTrue(
                    "$theme outline contrast",
                    contrast(colors.surface, colors.outline) >= 3f,
                )
            }
        }
    }

    @Test
    fun themeSurvivesNewStoreInstance() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = AppearanceSettingsStore(context)
        val previous = first.currentTheme()
        try {
            assertTrue(first.setTheme(AppearanceTheme.AMOLED).isSuccess)
            assertEquals(AppearanceTheme.AMOLED, AppearanceSettingsStore(context).currentTheme())
        } finally {
            first.setTheme(previous)
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
