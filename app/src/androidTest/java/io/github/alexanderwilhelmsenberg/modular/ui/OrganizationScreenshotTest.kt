package io.github.alexanderwilhelmsenberg.modular.ui

import android.graphics.Bitmap
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.appearance.AppearanceTheme
import io.github.alexanderwilhelmsenberg.modular.appearance.ModularTheme
import io.github.alexanderwilhelmsenberg.modular.organization.FolderCondition
import io.github.alexanderwilhelmsenberg.modular.organization.FolderDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.FolderMatchMode
import io.github.alexanderwilhelmsenberg.modular.organization.FolderVisibilityMode
import io.github.alexanderwilhelmsenberg.modular.organization.MegaSorterState
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import io.github.alexanderwilhelmsenberg.modular.organization.SorterDestination
import io.github.alexanderwilhelmsenberg.modular.organization.SorterRowState
import io.github.alexanderwilhelmsenberg.modular.organization.SorterSide
import io.github.alexanderwilhelmsenberg.modular.organization.WeeklyInterval
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** Deterministic native fixtures for the organization visual review matrix. */
class OrganizationScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val apps = listOf(
        LaunchableApp(AppKey("fixture.media", "Player", 0), "Music Player", 1, true, null),
        LaunchableApp(AppKey("fixture.work", "Mail", 0), "Work Mail", 1, true, null),
        LaunchableApp(AppKey("fixture.maps", "Maps", 0), "Maps", 1, true, null),
    )

    @Test fun categorySettings() {
        rule.setContent {
            ModularTheme(if (isSystemInDarkTheme()) AppearanceTheme.DARK else AppearanceTheme.LIGHT) {
                CategorySettingsUi(OrganizationSnapshot(), apps,
                    onUpdate = { Result.success(OrganizationSnapshot()) },
                    onManageFolders = {}, onStartMegaSorter = {})
            }
        }
        rule.onNodeWithTag("categories-configure").performClick()
        rule.onNodeWithTag("category:games").assertIsDisplayed()
        rule.onNodeWithTag("categories-manage-folders").assertExists()
        capture("categories", entireScreen = true)
    }

    @Test fun folderPopup() {
        val folder = FolderDefinition("media-folder", "Evening media", apps.map { it.key })
        rule.setContent {
            ModularTheme(if (isSystemInDarkTheme()) AppearanceTheme.DARK else AppearanceTheme.LIGHT) {
                FolderPopup(folder, apps, emptySet(), emptySet(), {}, {}, {}, {}, {},
                    iconForApp = { null })
            }
        }
        rule.onNodeWithTag("folder-popup:media-folder").assertIsDisplayed()
        rule.onNodeWithTag("folder-app:${apps.first().key.stableId}").assertIsDisplayed()
        rule.onNodeWithText("Close").assertIsDisplayed()
        capture("folder-popup", entireScreen = true)
    }

    @Test fun stagedFolderRules() {
        val headset = FolderCondition.Headset("headset-rule")
        val schedule = FolderCondition.Schedule("schedule-rule", intervals = listOf(WeeklyInterval()))
        rule.setContent {
            var folder by remember { mutableStateOf(FolderDefinition("rules-folder", "Commute",
                visibilityMode = FolderVisibilityMode.SMART, matchMode = FolderMatchMode.ANY,
                conditionIds = listOf(headset.id, schedule.id))) }
            var conditions by remember { mutableStateOf(listOf<FolderCondition>(headset, schedule)) }
            ModularTheme(if (isSystemInDarkTheme()) AppearanceTheme.DARK else AppearanceTheme.LIGHT) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text(folder.name)
                    FolderRuleSettings(folder, conditions, null, { folder = it }, { conditions = it })
                }
            }
        }
        rule.onNodeWithText("Smart visibility").assertIsDisplayed()
        rule.onNodeWithText("Match any").assertIsDisplayed()
        rule.onNodeWithText("Headset connected").assertIsDisplayed()
        capture("folder-rules")
    }

    @Test fun megaSorter() {
        val state = MegaSorterState(
            rows = apps.map { SorterRowState(it) },
            left = SorterDestination("work", "Work", SorterSide.LEFT),
            right = SorterDestination("games", "Games", SorterSide.RIGHT),
        )
        rule.setContent {
            ModularTheme(if (isSystemInDarkTheme()) AppearanceTheme.DARK else AppearanceTheme.LIGHT) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(top = 120.dp)) {
                        state.rows.forEach { row ->
                            MegaSorterRow(row, state, onAssign = {}) {
                                HomeAppRow(row.app, enabled = true, onClick = {})
                            }
                        }
                    }
                    MegaSorterOverlay(state, onExit = {}, onUndo = {})
                }
            }
        }
        rule.onNodeWithTag("sorter-row:${apps.first().key.stableId}").assertIsDisplayed()
        rule.onNodeWithContentDescription("Exit Mega Sorter").assertIsDisplayed()
        rule.onNodeWithText("Games →").assertIsDisplayed()
        capture("mega-sorter")
    }

    private fun capture(name: String, entireScreen: Boolean = false) {
        rule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = context.resources.configuration
        val night = if ((config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
            "dark" else "light"
        val font = String.format(Locale.ROOT, "%.1f", config.fontScale)
        val file = File(requireNotNull(context.getExternalFilesDir("organization-screenshots")),
            "organization-$name-${config.screenWidthDp}dp-$night-font$font.png")
        FileOutputStream(file).use { output ->
            val bitmap = if (entireScreen) InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                else rule.onRoot().captureToImage().asAndroidBitmap()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        // The test runner may uninstall the APK; preserve a public copy for CI pull.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val destinationDir = "/sdcard/Pictures/ModularOrganization"
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val destination = "$destinationDir/${file.name}"
        shell("mkdir -p $destinationDir")
        shell("cp ${file.absolutePath} $destination")
        check(shell("stat -c %s $destination").toLongOrNull() == file.length()) {
            "Could not preserve organization fixture screenshot"
        }
    }
}
