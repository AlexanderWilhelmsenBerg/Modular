package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.appearance.AppearanceHomeControls
import io.github.alexanderwilhelmsenberg.modular.appearance.AppearanceTheme
import io.github.alexanderwilhelmsenberg.modular.appearance.HomeAppearanceSettings
import io.github.alexanderwilhelmsenberg.modular.appearance.ModularTheme
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppCustomizations
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.events.CoreEventBusDiagnostics
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Native screenshots of the real settings shell at the capture script's device sizes. */
class SettingsScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val apps = listOf(
        LaunchableApp(AppKey("fixture.calendar", "CalendarActivity", 0), "Calendar", 0, true, null),
        LaunchableApp(AppKey("fixture.music", "MusicActivity", 0), "Music", 0, true, null),
    )

    @Test fun root() {
        fixture(SettingsPage.ROOT)
        val tallViewport = InstrumentationRegistry.getInstrumentation().targetContext.resources
            .configuration.screenHeightDp >= 800
        val tags = if (tallViewport) listOf(
            "settings-root-title", "settings-open-productivity", "settings-open-appearance",
            "settings-open-addons", "settings-open-advanced",
        ) else listOf("settings-root-title", "settings-open-productivity", "settings-open-appearance")
        val bounds = tags.map { rule.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (above, below) ->
            assertTrue("Settings rows overlap", above.bottom <= below.top)
        }
        capture("root")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-open-advanced"))
        rule.onNodeWithTag("settings-open-advanced").assertIsDisplayed()
    }

    @Test fun productivity() {
        fixture(SettingsPage.PRODUCTIVITY)
        rule.onNodeWithText("Productivity").assertIsDisplayed()
        capture("productivity")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("core-most-used-card"))
        rule.onNodeWithTag("core-most-used-card").assertIsDisplayed()
    }

    @Test fun appearance() {
        fixture(SettingsPage.APPEARANCE)
        rule.onNodeWithTag("appearance-light").assertIsDisplayed()
        capture("appearance")
    }

    @Test fun addons() {
        fixture(SettingsPage.ADDONS)
        rule.onNodeWithText("Add-ons").assertIsDisplayed()
        rule.onNodeWithText("Refresh module list").assertIsDisplayed()
        capture("addons")
    }

    @Test fun advanced() {
        fixture(SettingsPage.ADVANCED)
        rule.onNodeWithText("Advanced").assertIsDisplayed()
        capture("advanced")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-app-info"))
        rule.onNodeWithTag("settings-app-info").assertIsDisplayed()
    }

    private fun fixture(page: SettingsPage) {
        rule.setContent {
            val theme = AppearanceTheme.fromId(
                InstrumentationRegistry.getArguments().getString("settingsTheme"),
            )
            ModularTheme(theme) {
                ModularApp(
                    homeRoleAvailable = true,
                    homeRoleHeld = true,
                    modules = listOf(ModuleRecord(
                        component = ComponentName("fixture.sort", "fixture.sort.SortService"),
                        moduleId = "Example Sort",
                    )),
                    appCatalog = AppCatalog(apps = apps),
                    instances = emptyList(),
                    contexts = emptyList(),
                    activeContext = ActiveContextState.NONE,
                    compositionLoadError = null,
                    contextActivationError = null,
                    eventBusDiagnostics = CoreEventBusDiagnostics(),
                    moduleSecurity = emptyList(),
                    grantRecordCount = 0,
                    grantLoadError = null,
                    onRequestHomeRole = {},
                    onRefreshModules = {},
                    onRefreshApps = {},
                    onActivateContext = {},
                    onDeactivateContext = {},
                    initialPage = page,
                    coreFeaturesContent = {
                        CoreMostUsedSettingsUi(
                            enabled = true,
                            days = 7,
                            suggestedCount = 8,
                            pinnedKeys = emptyList(),
                            unavailablePinnedKeys = emptySet(),
                            hasUsageAccess = false,
                            catalog = apps,
                            onSetEnabled = { Result.success(Unit) },
                            onSetDays = { Result.success(Unit) },
                            onSetSuggestedCount = { Result.success(Unit) },
                            onSetPinned = { _, _ -> Result.success(Unit) },
                            onReorderPins = { Result.success(Unit) },
                            onOpenUsageAccess = {},
                            onOpenAppInfo = {},
                            onRefresh = {},
                        )
                        SettingsSectionLabel("Organize apps")
                        SettingsRow("Categories", "Create and arrange pages for your apps",
                            SettingsIcon.CATEGORIES, onClick = {},
                            trailing = { Switch(checked = false, onCheckedChange = {}) })
                        SettingsRow("Folders", "Group apps and set when they appear",
                            SettingsIcon.FOLDER, onClick = {})
                        SettingsRow("Mega Sorter", "Sort apps into categories side by side",
                            SettingsIcon.SORT, onClick = {})
                    },
                    appearanceContent = {
                        AppearanceHomeControls(HomeAppearanceSettings(), {}, {}, {})
                        HiddenAppsControls(apps, AppCustomizations(hidden = setOf(apps[1].key.stableId)), {})
                    },
                    compositionContent = { Text("No custom Home composition") },
                )
            }
        }
    }

    private fun capture(scene: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configuration = context.resources.configuration
        val theme = AppearanceTheme.fromId(
            InstrumentationRegistry.getArguments().getString("settingsTheme"),
        ).id
        val font = String.format(Locale.ROOT, "%.1f", configuration.fontScale)
        val file = File(requireNotNull(context.getExternalFilesDir("settings-screenshots")),
            "settings-$scene-${configuration.screenWidthDp}dp-$theme-font$font.png")
        FileOutputStream(file).use { output ->
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        val destinationDir = "/sdcard/Pictures/ModularSettings"
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText().trim() }
        shell("mkdir -p $destinationDir")
        val destination = "$destinationDir/${file.name}"
        shell("cp ${file.absolutePath} $destination")
        check(shell("stat -c %s $destination").toLongOrNull() == file.length()) {
            "Could not preserve settings screenshot"
        }
    }
}
