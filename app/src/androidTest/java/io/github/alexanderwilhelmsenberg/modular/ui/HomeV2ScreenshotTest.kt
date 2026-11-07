package io.github.alexanderwilhelmsenberg.modular.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.appearance.AppearanceTheme
import io.github.alexanderwilhelmsenberg.modular.appearance.ModularTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** Stable fixture captures for visual review at emulator sizes selected by the capture script. */
class HomeV2ScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private val apps = (0..25).flatMap { letter ->
        (1..3).map { number ->
            val name = "${('A'.code + letter).toChar()} sample app $number"
            LaunchableApp(AppKey("fixture.$letter.$number", "fixture.$letter.$number.Main", 0), name, 1, true, null)
        }
    }

    private fun fixtureIcon() = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.rgb(37, 112, 172)
        canvas.drawRoundRect(0f, 0f, 40f, 40f, 9f, 9f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 25f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("M", 20f, 29f, paint)
    }.asImageBitmap()

    @Test fun restingListAndBottomControls() {
        val icon = fixtureIcon()
        composeRule.setContent {
            ModularTheme(if (isSystemInDarkTheme()) AppearanceTheme.DARK else AppearanceTheme.LIGHT) {
                HomeScreen(AppCatalog(apps = apps), {}, {}, {}, {}, showMakeDefault = false, iconForApp = { icon })
            }
        }
        composeRule.onNodeWithTag("section:A").assertIsDisplayed()
        composeRule.onNodeWithTag("alphabet-rail").assertIsDisplayed()
        composeRule.onNodeWithTag("home-search").assertIsDisplayed()
        capture("rest")
    }

    @Test fun pressedRailShowsSelectedBubbleAndKeepsAppUnlaunched() {
        var launches = 0
        val icon = fixtureIcon()
        composeRule.setContent {
            ModularTheme(if (isSystemInDarkTheme()) AppearanceTheme.DARK else AppearanceTheme.LIGHT) {
                HomeScreen(AppCatalog(apps = apps), { launches++ }, {}, {}, {}, showMakeDefault = false, iconForApp = { icon })
            }
        }
        val rail = composeRule.onNodeWithTag("alphabet-rail")
        val alphabet = HomeSectionBuilder(Locale.getDefault()).alphabet
        val position = alphabet.indexOf("K")
        assertTrue("K must be in the rail", position >= 0)
        rail.performTouchInput {
            down(Offset(width - 8f, height * (position + 0.5f) / alphabet.size))
        }
        composeRule.onNodeWithTag("alphabet-bubble").assertIsDisplayed()
        capture("rail-k")
        rail.performTouchInput { up() }
        composeRule.runOnIdle { assertEquals(0, launches) }
    }

    @Test fun pullDownRetainsRealTappableRow() {
        var launches = 0
        val first = apps.first()
        val icon = fixtureIcon()
        composeRule.setContent {
            ModularTheme(if (isSystemInDarkTheme()) AppearanceTheme.DARK else AppearanceTheme.LIGHT) {
                HomeScreen(AppCatalog(apps = apps), { launches++ }, {}, {}, {}, showMakeDefault = false, iconForApp = { icon })
            }
        }
        val row = composeRule.onNodeWithTag("app-row:${first.key.stableId}")
        val initialTop = row.fetchSemanticsNode().boundsInRoot.top
        row.performTouchInput {
            down(center)
            moveTo(center + Offset(0f, 650f))
            up()
        }
        composeRule.waitForIdle()
        val loweredTop = row.fetchSemanticsNode().boundsInRoot.top
        assertTrue("Pull-down should retain lowered app hit targets", loweredTop > initialTop + 40f)
        assertTrue("First app should be reachable near mid-screen", loweredTop > composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height * 0.35f)
        composeRule.runOnIdle { assertEquals(0, launches) }
        capture("reachability")
        row.performClick()
        composeRule.runOnIdle { assertEquals(1, launches) }
    }

    @Test fun wallpaperAndFeaturedEmptyState() {
        val icon = fixtureIcon()
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFFFF5B8), Color(0xFF14406F), Color(0xFFFFA58C))))) {
                ModularTheme(AppearanceTheme.DARK) {
                    HomeScreen(
                        AppCatalog(apps = apps), {}, {}, {}, {}, showMakeDefault = false,
                        wallpaperMode = true, wallpaperDimmingPercent = 0,
                        featuredApps = emptyList(), onOpenFeaturedSettings = {}, iconForApp = { icon },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("section:★").assertIsDisplayed()
        composeRule.onNodeWithTag("featured-settings").assertIsDisplayed()
        capture("wallpaper-featured")
    }

    @Test fun amoledSurfaceKeepsIconsAndBottomControlsVisible() {
        val icon = fixtureIcon()
        composeRule.setContent {
            ModularTheme(AppearanceTheme.AMOLED) {
                HomeScreen(AppCatalog(apps = apps), {}, {}, {}, {},
                    showMakeDefault = false, iconForApp = { icon })
            }
        }
        composeRule.onNodeWithTag("alphabet-rail").assertIsDisplayed()
        composeRule.onNodeWithTag("home-settings").assertIsDisplayed()
        capture("amoled")
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = context.resources.configuration
        val width = configuration.screenWidthDp
        val night = if ((configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        val font = String.format(Locale.ROOT, "%.1f", configuration.fontScale)
        val dir = requireNotNull(context.getExternalFilesDir("home-v2-screenshots"))
        val file = File(dir, "home-$name-${width}dp-$night-font$font.png")
        FileOutputStream(file).use { out ->
            check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out))
        }
    }
}
