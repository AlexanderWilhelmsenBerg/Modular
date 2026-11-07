package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.appearance.AppearanceTheme
import io.github.alexanderwilhelmsenberg.modular.appearance.ModularTheme
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Rule
import org.junit.Test

/** Native themed fixtures across the viewport, font and light/dark matrix in the capture script. */
class CoreMostUsedScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val apps = listOf(
        app("Calendar", "fixture.calendar", "CalendarActivity", 0),
        app("Messages", "fixture.messages", "MessagesActivity", 0),
        app("Music", "fixture.music", "MusicActivity", 0),
        app("Maps", "fixture.maps", "MapsActivity", 0),
    )

    @Test fun disabledCard() {
        fixture(enabled = false, hasUsageAccess = false)
        rule.onNodeWithTag("core-most-used-card").assertIsDisplayed()
        capture("disabled-card")
    }

    @Test fun enabledWithoutAccessWithPins() {
        fixture(enabled = true, hasUsageAccess = false, pins = listOf(apps[0].key, apps[1].key))
        rule.onNodeWithText("Configure Most Used").performClick()
        rule.onNodeWithText("Calendar").performScrollTo().assertIsDisplayed()
        capture("enabled-pins-access-off")
    }

    @Test fun grantedAccessWithoutHistory() {
        fixture(
            enabled = true,
            hasUsageAccess = true,
            statusMessage = "No usage history yet. Saved pins still appear on Home.",
        )
        rule.onNodeWithText("Configure Most Used").performClick()
        rule.onNodeWithText("No usage history yet.", substring = true).performScrollTo().assertIsDisplayed()
        capture("access-on-no-history")
    }

    private fun fixture(
        enabled: Boolean,
        hasUsageAccess: Boolean,
        pins: List<AppKey> = emptyList(),
        statusMessage: String? = null,
    ) {
        rule.setContent {
            NativeTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                    CoreMostUsedSettingsUi(
                        enabled = enabled,
                        days = 7,
                        suggestedCount = 8,
                        pinnedKeys = pins,
                        unavailablePinnedKeys = emptySet(),
                        hasUsageAccess = hasUsageAccess,
                        catalog = apps,
                        onSetEnabled = { Result.success(Unit) },
                        onSetDays = { Result.success(Unit) },
                        onSetSuggestedCount = { Result.success(Unit) },
                        onSetPinned = { _, _ -> Result.success(Unit) },
                        onReorderPins = { Result.success(Unit) },
                        onOpenUsageAccess = {},
                        onOpenAppInfo = {},
                        onRefresh = {},
                        statusMessage = statusMessage,
                    )
                }
            }
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configuration = context.resources.configuration
        val width = configuration.screenWidthDp
        val mode = if ((configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
            "dark" else "light"
        val font = String.format(Locale.ROOT, "%.1f", configuration.fontScale)
        val file = File(requireNotNull(context.getExternalFilesDir("core-most-used-screenshots")),
            "core-most-used-$name-${width}dp-$mode-font$font.png")
        FileOutputStream(file).use { output ->
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        // Connected tests remove the target APK after completion. Preserve the PNG first.
        val destinationDir = "/sdcard/Pictures/ModularCoreMostUsed"
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText().trim() }
        shell("mkdir -p $destinationDir")
        val destination = "$destinationDir/${file.name}"
        shell("cp ${file.absolutePath} $destination")
        check(shell("stat -c %s $destination").toLongOrNull() == file.length()) {
            "Could not preserve Core Most Used screenshot"
        }
    }

    private fun app(label: String, packageName: String, activityName: String, profile: Long) =
        LaunchableApp(AppKey(packageName, activityName, profile), label, 0, true, null)

    @Composable private fun NativeTheme(content: @Composable () -> Unit) {
        ModularTheme(if (isSystemInDarkTheme()) AppearanceTheme.DARK else AppearanceTheme.LIGHT, content = content)
    }
}
