package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import io.github.alexanderwilhelmsenberg.modular.appearance.AppearanceSettingsStore
import io.github.alexanderwilhelmsenberg.modular.appearance.AppearanceTheme
import io.github.alexanderwilhelmsenberg.modular.organization.HomePageId
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Requires the independently installed addon-files debug APK on the device. */
class FilesCrossApkHomeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun addFilesFromSettingsAndRenderItsEmbeddedPage() {
        val store = OrganizationStore(rule.activity)
        val requestedTheme = InstrumentationRegistry.getArguments().getString("files_theme")
            ?.let { AppearanceTheme.valueOf(it) }
        val appearance = AppearanceSettingsStore(rule.activity)
        val previousTheme = requestedTheme?.let { runBlocking { appearance.currentTheme() } }
        var addedComponent: String? = null
        try {
            if (requestedTheme != null) {
                runBlocking { appearance.setTheme(requestedTheme).getOrThrow() }
                rule.activityRule.scenario.recreate()
            }
            rule.waitUntil(20_000) {
                rule.onAllNodesWithTag("home-settings").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("home-settings").performClick()
            rule.onNodeWithTag("settings-open-productivity").performClick()
            rule.onNodeWithTag("settings-open-home").performClick()
            rule.onNodeWithTag("workspace-settings-content").assertIsDisplayed()

            val add = "Add Modular Files to Home screens"
            rule.waitUntil(30_000) {
                runCatching { rule.onNodeWithText(add).performScrollTo().assertIsDisplayed() }.isSuccess
            }
            rule.onNodeWithText(add).performClick()
            rule.onNodeWithText("Right of Home").performClick()
            rule.waitUntil(15_000) {
                runBlocking { store.currentSnapshot().modulePages.any { it.name == "Modular Files" } }
            }
            val saved = runBlocking { store.currentSnapshot() }
            val filesPage = saved.modulePages.single { it.name == "Modular Files" }
            addedComponent = filesPage.component
            val homeIndex = saved.pageOrder.indexOf(HomePageId.Home)
            assertEquals(HomePageId.Module(filesPage.component), saved.pageOrder[homeIndex + 1])
            assertEquals(filesPage.component, runBlocking {
                OrganizationStore(rule.activity).currentSnapshot().modulePages.single {
                    it.name == "Modular Files"
                }.component
            })

            rule.onNodeWithTag("settings-back").performClick()
            rule.onNodeWithTag("settings-back").performClick()
            rule.onNodeWithTag("settings-back").performClick()
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("launcher-pages").fetchSemanticsNodes().isNotEmpty()
            }
            swipePage(left = true)
            rule.waitUntil(20_000) {
                rule.onAllNodesWithTag("files.page.overview").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("files.page.overview").assertIsDisplayed()
            rule.onNodeWithTag("files.trash.badge").assertIsDisplayed()
            rule.onNodeWithTag("alphabet-rail").assertDoesNotExist()
            rule.onNodeWithTag("home-search-field").assertIsDisplayed()
            assertTrue(rule.onAllNodesWithTag("workspace-apps-action").fetchSemanticsNodes().isNotEmpty())
            rule.waitForIdle()
            if (InstrumentationRegistry.getArguments().getString("files_capture") == "true") captureOverview()

            repeat(2) {
                swipePage(left = false)
                rule.waitUntil(15_000) {
                    runCatching {
                        rule.onNode(hasTestTag("launcher-page:home") and
                            hasAnyAncestor(hasTestTag("launcher-pages"))).assertIsDisplayed()
                    }.isSuccess
                }
                swipePage(left = true)
                rule.waitUntil(20_000) {
                    runCatching { rule.onNodeWithTag("files.page.overview").assertIsDisplayed() }.isSuccess
                }
                rule.onNodeWithTag("home-search-field").assertIsDisplayed()
                rule.onNodeWithTag("files.trash.badge").assertIsDisplayed()
            }

            rule.onNodeWithText("Add folder").performClick()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue("Embedded Files did not open Android's tree picker",
                device.wait(Until.hasObject(By.pkg(Pattern.compile(".*documentsui.*"))), 10_000))
            device.pressBack()
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("files.page.overview").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("files.page.overview").assertIsDisplayed()
            rule.onNodeWithTag("files.trash.badge").assertIsDisplayed()
        } finally {
            addedComponent?.let { component ->
                runBlocking {
                    val current = store.currentSnapshot()
                    if (current.modulePages.any { it.component == component }) {
                        store.update(OrganizationBatch(listOf(OrganizationEdit.RemoveModulePage(component)),
                            current.revision)).getOrThrow()
                    }
                }
            }
            if (previousTheme != null) runBlocking { appearance.setTheme(previousTheme).getOrThrow() }
        }
    }

    private fun swipePage(left: Boolean) {
        rule.onNodeWithTag("launcher-pages").performTouchInput {
            val y = height * 0.5f
            val start = Offset(width * (if (left) 0.82f else 0.18f), y)
            down(start)
            for (step in 1..12) moveTo(Offset(
                start.x + width * (if (left) -0.64f else 0.64f) * step / 12f, y), 20L)
            up()
        }
        rule.waitForIdle()
    }

    private fun captureOverview() {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configuration = context.resources.configuration
        val theme = InstrumentationRegistry.getArguments().getString("files_theme")
            ?.lowercase(Locale.ROOT) ?: if (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        val font = String.format(Locale.ROOT, "%.1f", configuration.fontScale)
        val filename = "files-overview-${configuration.screenWidthDp}dp-$theme-font$font.png"
        val file = File(requireNotNull(context.getExternalFilesDir("files-screenshots")), filename)
        FileOutputStream(file).use { output ->
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
        }
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText().trim() }
        shell("mkdir -p /sdcard/Pictures/ModularFiles")
        shell("cp ${file.absolutePath} /sdcard/Pictures/ModularFiles/$filename")
        assertEquals(file.length(), shell("stat -c %s /sdcard/Pictures/ModularFiles/$filename").toLong())
    }
}
