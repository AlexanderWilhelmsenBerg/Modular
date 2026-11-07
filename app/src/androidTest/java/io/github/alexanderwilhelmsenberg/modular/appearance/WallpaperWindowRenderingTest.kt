package io.github.alexanderwilhelmsenberg.modular.appearance

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the real Activity surface; a createComposeRule fixture misses window buffer reuse. */
class WallpaperWindowRenderingTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun transparentHomeClearsMovedContentAndOpaqueSettingsPixels() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("home-search").fetchSemanticsNodes().isNotEmpty()
        }
        val activity = composeRule.activity
        var scene by mutableStateOf(Scene())
        composeRule.runOnUiThread {
            activity.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        if (scene.settings) {
                            Box(Modifier.fillMaxSize().background(Color(0xFFB8E4F7)))
                        } else {
                            WallpaperHomeScrim(scene.dimming, Modifier.fillMaxSize())
                            if (scene.markVisible) {
                                Box(
                                    Modifier.align(Alignment.Center)
                                        .size(80.dp)
                                        .background(Color.Magenta),
                                )
                            }
                        }
                    }
                }
            }
            assertTrue(applyHomeWindowAppearance(activity, AppearanceTheme.LIGHT, true, true))
        }
        awaitCommittedFrame()
        assertWindow(wallpaper = true)
        assertEquals(0, AndroidColor.alpha(centerPixel()))

        composeRule.runOnUiThread { scene = scene.copy(markVisible = true) }
        awaitCommittedFrame()
        assertEquals(AndroidColor.MAGENTA, centerPixel())

        composeRule.runOnUiThread { scene = scene.copy(markVisible = false) }
        awaitCommittedFrame()
        assertEquals("Moved content must leave transparent pixels", 0, AndroidColor.alpha(centerPixel()))

        composeRule.runOnUiThread {
            applyHomeWindowAppearance(activity, AppearanceTheme.LIGHT, true, false)
            scene = scene.copy(settings = true)
        }
        awaitCommittedFrame()
        assertWindow(wallpaper = false)
        assertEquals(AndroidColor.rgb(184, 228, 247), centerPixel())

        composeRule.runOnUiThread {
            assertTrue(applyHomeWindowAppearance(activity, AppearanceTheme.LIGHT, true, true))
            scene = Scene()
        }
        awaitCommittedFrame()
        assertWindow(wallpaper = true)
        assertEquals("Settings pixels must not remain under Home", 0, AndroidColor.alpha(centerPixel()))

        composeRule.runOnUiThread { scene = scene.copy(dimming = 35) }
        awaitCommittedFrame()
        val dimmed = centerPixel()
        assertEquals(0, AndroidColor.red(dimmed))
        assertEquals(0, AndroidColor.green(dimmed))
        assertEquals(0, AndroidColor.blue(dimmed))
        assertTrue("Expected 35% scrim alpha, got ${AndroidColor.alpha(dimmed)}",
            AndroidColor.alpha(dimmed) in 85..93)

        repeat(3) {
            composeRule.runOnUiThread { scene = scene.copy(markVisible = true) }
            awaitCommittedFrame()
            assertEquals(AndroidColor.MAGENTA, centerPixel())
            composeRule.runOnUiThread { scene = scene.copy(markVisible = false) }
            awaitCommittedFrame()
            assertEquals("Dimming must not accumulate over old frames", dimmed, centerPixel())
        }
        composeRule.runOnUiThread {
            applyHomeWindowAppearance(activity, AppearanceTheme.LIGHT, true, false)
            scene = scene.copy(settings = true)
        }
        awaitCommittedFrame()
        composeRule.runOnUiThread {
            applyHomeWindowAppearance(activity, AppearanceTheme.LIGHT, true, true)
            scene = scene.copy(settings = false)
        }
        awaitCommittedFrame()
        assertEquals("Dimmed Home must clear Settings pixels too", dimmed, centerPixel())
    }

    private fun assertWindow(wallpaper: Boolean) {
        composeRule.runOnIdle {
            val window = composeRule.activity.window
            assertEquals(
                if (wallpaper) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE,
                window.attributes.format,
            )
            assertEquals(
                wallpaper,
                window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0,
            )
        }
    }

    private fun awaitCommittedFrame() {
        composeRule.waitForIdle()
        val committed = CountDownLatch(1)
        composeRule.runOnUiThread {
            val decor = composeRule.activity.window.decorView
            decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            decor.invalidate()
        }
        assertTrue("Window frame was not committed", committed.await(5, TimeUnit.SECONDS))
    }

    private fun centerPixel(): Int {
        val window = composeRule.activity.window
        val decor = window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(window, bitmap, { code -> result = code; copied.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("PixelCopy did not finish", copied.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2).also { bitmap.recycle() }
    }

    private data class Scene(
        val settings: Boolean = false,
        val markVisible: Boolean = false,
        val dimming: Int = 0,
    )
}
