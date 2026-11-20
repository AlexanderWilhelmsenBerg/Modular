package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.os.Bundle
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class DashboardPlacementTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    @Test fun twoDashboardUrlsRemainIndependentAcrossHomeSwipes() {
        assumeTrue(Build.VERSION.SDK_INT >= 33 && InstrumentationRegistry.getArguments().getString("dashboardFixture") == "true")
        try { verifyPlacements() } catch (failure: Throwable) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val xml = java.io.ByteArrayOutputStream()
            device.dumpWindowHierarchy(xml)
            val log = device.executeShellCommand("logcat -d -s ModuleWorkspaceSurface:D WorkspaceSurfaceEndpoint:D WorkspacePagerState:D AndroidRuntime:E").takeLast(18000)
            val lifecycleEvents = rule.activity.getSharedPreferences("module_management", 0).getString("lifecycle_events", "")
            (xml.toString("UTF-8") + "\n" + lifecycleEvents + "\n" + log).chunked(2400).forEach { chunk ->
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", chunk) })
            }
            throw failure
        }
    }

    private fun verifyPlacements() {
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
        fun waitPage(key: String) {
            rule.waitUntil(20_000) {
                runCatching {
                    val pager = rule.onNodeWithTag("launcher-pages").fetchSemanticsNode().boundsInWindow
                    val page = rule.onNode(hasTestTag("launcher-page:$key") and hasAnyAncestor(hasTestTag("launcher-pages")))
                    page.assertIsDisplayed()
                    val bounds = page.fetchSemanticsNode().boundsInWindow
                    kotlin.math.abs(bounds.left - pager.left) <= 1f && kotlin.math.abs(bounds.right - pager.right) <= 1f
                }.getOrDefault(false)
            }
        }
        fun swipe(right: Boolean) {
            val w = device.displayWidth
            val y = device.displayHeight * 2 / 5
            device.swipe(if (right) w / 5 else w * 4 / 5, y, if (right) w * 4 / 5 else w / 5, y, 38)
            rule.waitForIdle()
        }
        fun open(name: String) {
            val open = By.text(java.util.regex.Pattern.compile("open dashboard", java.util.regex.Pattern.CASE_INSENSITIVE))
            // Pump the Compose host while the independent service binds and its surface attaches.
            rule.waitUntil(20_000) { device.hasObject(open) }
            val button = requireNotNull(device.findObject(open))
            val input = device.findObjects(By.clazz("android.widget.EditText"))
                .first { it.visibleBounds.centerY() < button.visibleBounds.centerY() }
            input.text = "http://127.0.0.1:8187/$name.html"
            button.click()
            rule.waitUntil(25_000) { device.hasObject(By.text("Dashboard $name")) || device.hasObject(By.desc("Dashboard $name")) }
        }
        waitPage("home")
        swipe(true); waitPage("module:${pages[0].placementId}"); open("Kitchen")
        swipe(false)
        waitPage("home")
        swipe(false); waitPage("module:${pages[1].placementId}"); open("Garage")
        swipe(true)
        waitPage("home")
        swipe(true)
        rule.waitUntil(25_000) { device.hasObject(By.text("Dashboard Kitchen")) || device.hasObject(By.desc("Dashboard Kitchen")) }
    }
}
