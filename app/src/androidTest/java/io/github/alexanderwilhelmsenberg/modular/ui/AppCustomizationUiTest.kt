package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.activity.ComponentActivity
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppCustomizations
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class AppCustomizationUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun resizeForKeyboard() {
        rule.activityRule.scenario.onActivity {
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test fun renameHideSearchRestoreAndReset() {
        val zulu = app("com.nvidia.shieldtv", "Zulu")
        val beta = app("example.beta", "Beta")
        val rawApps = listOf(zulu, beta)
        var customizations by mutableStateOf(AppCustomizations())
        var renameTarget by mutableStateOf<LaunchableApp?>(null)
        var settings by mutableStateOf(false)

        rule.setContent {
            if (settings) {
                Column {
                    TextButton(onClick = { settings = false }) { Text("Back to Home") }
                    HiddenAppsControls(
                        apps = rawApps,
                        customizations = customizations,
                        onUnhide = { key ->
                            customizations = customizations.copy(hidden = customizations.hidden - key.stableId)
                        },
                    )
                }
            } else {
                HomeScreen(
                    catalog = AppCatalog(apps = customizations.visibleApps(rawApps)),
                    featuredApps = listOf(zulu),
                    orderedApps = null,
                    onLaunch = {},
                    onRefresh = {},
                    onMakeDefault = {},
                    onOpenSettings = { settings = true },
                    onRenameApp = { renameTarget = it },
                    onHideApp = { app ->
                        customizations = customizations.copy(hidden = customizations.hidden + app.key.stableId)
                    },
                )
            }
            renameTarget?.let { target ->
                RenameAppDialog(
                    app = target,
                    currentLabel = customizations.labelFor(zulu),
                    originalLabel = zulu.label,
                    onSave = { label ->
                        val labels = customizations.labels.toMutableMap()
                        if (label == null) labels.remove(target.key.stableId)
                        else labels[target.key.stableId] = label
                        customizations = customizations.copy(labels = labels)
                        renameTarget = null
                    },
                    onDismiss = { renameTarget = null },
                )
            }
        }

        rule.onAllNodesWithTag("app-row:${zulu.key.stableId}").onFirst().performTouchInput { longClick() }
        rule.onNodeWithTag("app-action:rename").performClick()
        rule.onNodeWithTag("rename-app-input").performTextReplacement("Alpha")
        rule.onNodeWithText("Save").performClick()
        rule.onAllNodesWithText("Alpha").assertCountEquals(2)
        rule.onNodeWithText("Zulu").assertDoesNotExist()
        val alphaTop = rule.onAllNodesWithTag("app-row:${zulu.key.stableId}").onLast()
            .fetchSemanticsNode().boundsInRoot.top
        val betaTop = rule.onNodeWithTag("app-row:${beta.key.stableId}").fetchSemanticsNode().boundsInRoot.top
        assertEquals(true, alphaTop < betaTop)

        rule.onAllNodesWithTag("app-row:${zulu.key.stableId}").onFirst().performTouchInput { longClick() }
        rule.onNodeWithTag("app-action:hide").performClick()
        rule.onAllNodesWithTag("app-row:${zulu.key.stableId}").assertCountEquals(0)
        rule.onAllNodesWithText("Alpha").assertCountEquals(0)
        rule.onNodeWithTag("home-search-field").performClick()
        rule.onNodeWithTag("home-search-field").performTextInput("nvidia")
        rule.onNodeWithText("No matching apps").assertIsDisplayed()

        rule.onNodeWithTag("home-settings").performClick()
        rule.onNodeWithTag("settings-hidden-apps").assertIsDisplayed().performClick()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithTag("unhide-app:${zulu.key.stableId}").performClick()
        rule.onNodeWithText("No installed apps are hidden.").assertIsDisplayed()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Hidden apps (0)").assertIsDisplayed()
        rule.onNodeWithText("Back to Home").performClick()
        rule.onAllNodesWithTag("app-row:${zulu.key.stableId}").assertCountEquals(2)
        rule.onAllNodesWithText("Alpha").assertCountEquals(2)

        rule.onAllNodesWithTag("app-row:${zulu.key.stableId}").onFirst().performTouchInput { longClick() }
        rule.onNodeWithTag("app-action:rename").performClick()
        rule.onNodeWithTag("rename-app-reset").performClick()
        rule.onAllNodesWithText("Zulu").assertCountEquals(2)
        rule.runOnIdle { assertEquals(emptyMap<String, String>(), customizations.labels) }
    }

    private fun app(packageName: String, label: String) = LaunchableApp(
        AppKey(packageName, "$packageName.Main", 0), label, 0, true, null,
    )
}
