package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class DashboardPlacementTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    @Test fun twoDashboardUrlsRemainIndependentAcrossHomeSwipes() {
        assumeTrue(Build.VERSION.SDK_INT >= 33 && InstrumentationRegistry.getArguments().getString("dashboardFixture") == "true")
        val store = OrganizationStore(rule.activity)
        rule.onNodeWithTag("home-settings").performClick()
        rule.onNodeWithTag("settings-open-productivity").performClick()
        rule.onNodeWithTag("settings-open-home").performClick()
        repeat(2) { index ->
            val add = rule.onNodeWithText("Add Home Assistant to Home screens")
            rule.waitUntil(20_000) { runCatching { add.performScrollTo().assertIsDisplayed() }.isSuccess }
            add.performClick()
            rule.onNodeWithText(if (index == 0) "Left of Home" else "Right of Home").performClick()
            rule.waitUntil(10_000) { runBlocking { store.currentSnapshot() }.modulePages.count { it.name == "Home Assistant" } == index + 1 }
        }
        val pages = runBlocking { store.currentSnapshot() }.modulePages.filter { it.name == "Home Assistant" }
        assertEquals(1, pages.map { it.component }.distinct().size)
        assertEquals(2, pages.map { it.placementId }.distinct().size)
        repeat(3) { rule.onNodeWithTag("settings-back").performClick() }
        fun swipe(right: Boolean) {
            val w = device.displayWidth
            val y = device.displayHeight * 2 / 5
            device.swipe(if (right) w / 5 else w * 4 / 5, y, if (right) w * 4 / 5 else w / 5, y, 38)
            rule.waitForIdle()
        }
        fun open(name: String) {
            val input = requireNotNull(device.wait(Until.findObject(By.desc("Dashboard URL")), 20_000))
            input.text = "http://127.0.0.1:8187/$name.html"
            requireNotNull(device.findObject(By.text("OPEN DASHBOARD")) ?: device.findObject(By.text("Open dashboard"))).click()
            assertTrue("Missing $name dashboard", device.wait(Until.hasObject(By.text("Dashboard $name")), 25_000))
        }
        swipe(true); open("Kitchen")
        swipe(false)
        rule.onNodeWithTag("home-settings").assertIsDisplayed()
        swipe(false); open("Garage")
        swipe(true)
        rule.onNodeWithTag("home-settings").assertIsDisplayed()
        swipe(true)
        assertTrue("Restored the wrong dashboard", device.wait(Until.hasObject(By.text("Dashboard Kitchen")), 20_000))
    }
}
