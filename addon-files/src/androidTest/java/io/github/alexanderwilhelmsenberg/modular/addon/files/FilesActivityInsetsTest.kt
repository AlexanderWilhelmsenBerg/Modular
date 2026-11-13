package io.github.alexanderwilhelmsenberg.modular.addon.files

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The standalone activity must keep its title clear of the physical status bar. */
class FilesActivityInsetsTest {
    @get:Rule val rule = createAndroidComposeRule<FilesSettingsActivity>()

    @Test fun settingsTitleStartsBelowStatusBar() {
        rule.waitUntil(15_000) {
            runCatching { rule.onNodeWithText("Files settings").assertExists() }.isSuccess
        }
        val statusBarBottom = rule.runOnIdle {
            val decor = rule.activity.window.decorView
            ViewCompat.getRootWindowInsets(decor)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        }
        assertTrue("Device must report a status bar inset", statusBarBottom > 0)
        val titleTop = rule.onNodeWithText("Files settings").fetchSemanticsNode().boundsInWindow.top
        assertTrue("Files title overlaps the status bar: title=$titleTop, bar=$statusBarBottom",
            titleTop >= statusBarBottom)
    }
}
