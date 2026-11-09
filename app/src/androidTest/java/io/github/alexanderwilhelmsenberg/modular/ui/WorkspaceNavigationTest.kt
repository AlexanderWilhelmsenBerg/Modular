package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import io.github.alexanderwilhelmsenberg.modular.apps.AndroidAppRepository
import io.github.alexanderwilhelmsenberg.modular.apps.AppCustomizationStore
import io.github.alexanderwilhelmsenberg.modular.organization.FolderDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.FolderPlacement
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationStore
import io.github.alexanderwilhelmsenberg.modular.mostused.MostUsedSettingsStore
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the composed launcher, rather than the isolated legacy HomeScreen. */
class WorkspaceNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun coldLandingAppsAndBackRestoreHome() {
        waitForHome()
        rule.onNodeWithTag("workspace-home").assertIsDisplayed()
        rule.onNodeWithTag("workspace-all-apps").assertIsNotDisplayed()

        rule.onNodeWithTag("workspace-apps-action").performClick()
        waitForAllApps()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForHome()
        rule.onNodeWithTag("workspace-all-apps").assertIsNotDisplayed()
    }

    @Test fun drawerOrdersMostUsedFoldersThenAlphabetAndKeepsSearchGlobal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val organization = OrganizationStore(context)
        val mostUsed = MostUsedSettingsStore(context)
        val appRepository = AndroidAppRepository(context)
        val original = runBlocking { organization.currentSnapshot() }
        val customizations = runBlocking { AppCustomizationStore(context).currentCustomizations() }
        @Suppress("UNCHECKED_CAST")
        val preferences = MostUsedSettingsStore::class.java.getDeclaredField("dataStore")
            .apply { isAccessible = true }.get(mostUsed) as DataStore<Preferences>
        val settingsKey = stringPreferencesKey("settings_json_v1")
        val originalSettings = runBlocking { preferences.data.first()[settingsKey] }
        val pinnedFolderId = UUID.randomUUID().toString()
        val alphabetFolderId = UUID.randomUUID().toString()
        var foldersCreated = false
        try {
            rule.waitUntil(15_000) { appRepository.catalog.value.apps.isNotEmpty() &&
                !appRepository.catalog.value.isRefreshing }
            val catalog = appRepository.catalog.value
            val available = customizations.visibleApps(catalog.apps).filter { it.enabled }
            val homeKeys = io.github.alexanderwilhelmsenberg.modular.organization.OrganizationProjector
                .project(catalog.copy(apps = available), customizations, original)
                .homeApps.mapTo(HashSet()) { it.key }
            val app = available.firstOrNull { it.key in homeKeys }
                ?: error("No visible Home app available for drawer fixture")
            val fixtureFolders = listOf(
                FolderDefinition(pinnedFolderId, "Grid pinned ${pinnedFolderId.take(8)}",
                    members = listOf(app.key), placement = FolderPlacement.MOST_USED),
                FolderDefinition(alphabetFolderId, "Grid alphabet ${alphabetFolderId.take(8)}",
                    members = listOf(app.key), placement = FolderPlacement.APP_LIST),
            )
            runBlocking {
                val current = organization.currentSnapshot()
                organization.update(OrganizationBatch(fixtureFolders.map(OrganizationEdit::CreateFolder),
                    current.revision)).getOrThrow()
            }
            foldersCreated = true
            runBlocking {
                mostUsed.setSuggestedCount(0).getOrThrow()
                mostUsed.setEnabled(true).getOrThrow()
                mostUsed.setPinned(app.key, true, catalog.apps.mapTo(HashSet()) { it.key }).getOrThrow()
            }
            waitForHome()
            rule.onNodeWithTag("section:★").assertIsNotDisplayed()
            rule.onNodeWithTag("folder-row:$pinnedFolderId").assertIsNotDisplayed()
            rule.onNodeWithTag("folder-row:$alphabetFolderId").assertIsNotDisplayed()

            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("rail:★").fetchSemanticsNodes().isNotEmpty()
            }
            tapRail("★")
            waitForAllApps()
            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithTag("section:★").assertIsDisplayed() }.isSuccess
            }
            captureDrawer()
            val firstAlpha = rule.onAllNodes(SemanticsMatcher("alphabet rail letter") { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)?.let {
                    it.startsWith("rail:") && it != "rail:★" && it != "rail:◇"
                } == true
            }).fetchSemanticsNodes().first().config[SemanticsProperties.TestTag].removePrefix("rail:")
            val indexForKey = rule.onNodeWithTag("home-list").fetchSemanticsNode()
                .config[SemanticsProperties.IndexForKey]
            val headerIndex = indexForKey("header:★")
            val appIndex = indexForKey("featured:${app.key.stableId}")
            val foldersIndex = indexForKey("header:Folders")
            val pinnedIndex = indexForKey("folder:$pinnedFolderId")
            val alphabetFolderIndex = indexForKey("folder:$alphabetFolderId")
            val alphaIndex = indexForKey("header:$firstAlpha")
            assertTrue("Drawer fixture entries must all be indexed",
                listOf(headerIndex, appIndex, foldersIndex, pinnedIndex,
                    alphabetFolderIndex, alphaIndex).all { it >= 0 })
            assertTrue("Most Used app must follow its header", headerIndex < appIndex)
            assertTrue("Folders must follow Most Used apps", appIndex < foldersIndex)
            assertTrue("Pinned folders precede alphabetical folders",
                foldersIndex < pinnedIndex && pinnedIndex < alphabetFolderIndex)
            assertTrue("Alphabet starts after Folders", alphabetFolderIndex < alphaIndex)

            tapRail("◇")
            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithTag("section:Folders").assertIsDisplayed() }.isSuccess
            }
            rule.onNodeWithTag("home-search-field").performTextInput(app.label)
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("app-row:${app.key.stableId}")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("home-search-clear").performClick()

            runBlocking { mostUsed.setEnabled(false).getOrThrow() }
            rule.waitUntil(15_000) {
                rule.onNodeWithTag("home-list").fetchSemanticsNode()
                    .config[SemanticsProperties.IndexForKey]("header:★") < 0
            }
            rule.onNodeWithTag("home-list").performScrollToNode(hasTestTag("section:Folders"))
            rule.onNodeWithTag("section:Folders").assertIsDisplayed()
            rule.onNodeWithTag("home-list").performScrollToNode(hasTestTag("folder-row:$pinnedFolderId"))
            rule.onNodeWithTag("folder-row:$pinnedFolderId").assertIsDisplayed()
            rule.onNodeWithTag("home-list").performScrollToNode(hasTestTag("folder-row:$alphabetFolderId"))
            rule.onNodeWithTag("folder-row:$alphabetFolderId").assertIsDisplayed()
        } finally {
            try {
                if (foldersCreated) runBlocking {
                    val current = organization.currentSnapshot()
                    organization.update(OrganizationBatch(listOf(
                        OrganizationEdit.DeleteFolder(pinnedFolderId),
                        OrganizationEdit.DeleteFolder(alphabetFolderId),
                    ), current.revision)).getOrThrow()
                }
            } finally {
                runBlocking {
                    preferences.edit { saved ->
                        if (originalSettings == null) saved.remove(settingsKey)
                        else saved[settingsKey] = originalSettings
                    }
                }
                appRepository.close()
            }
        }
    }

    private fun tapRail(label: String) {
        val glyph = rule.onNodeWithTag("rail:$label").fetchSemanticsNode().boundsInRoot
        val lane = rule.onNodeWithTag("alphabet-hit-lane")
        val bounds = lane.fetchSemanticsNode().boundsInRoot
        lane.performTouchInput {
            down(Offset(width / 2f, glyph.center.y - bounds.top))
            up()
        }
    }

    private fun captureDrawer() {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = context.resources.configuration
        val theme = if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            "dark" else "light"
        val font = String.format(Locale.ROOT, "%.1f", config.fontScale)
        val direction = if (config.layoutDirection == View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"
        val file = File(requireNotNull(context.getExternalFilesDir("workspace-screenshots")),
            "drawer-most-used-folders-${config.screenWidthDp}dp-$theme-font$font-$direction.png")
        FileOutputStream(file).use { output ->
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
        val destination = "/sdcard/Pictures/ModularWorkspace/${file.name}"
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }
        shell("mkdir -p /sdcard/Pictures/ModularWorkspace")
        shell("cp ${file.absolutePath} $destination")
        check(shell("stat -c %s $destination").toLongOrNull() == file.length()) {
            "Could not preserve drawer screenshot"
        }
    }

    @Test fun populatedHomeRailOpensTheRequestedAllAppsSection() {
        waitForHome()
        val section = rule.onAllNodes(SemanticsMatcher("populated letter") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.let {
                it.startsWith("rail:") && it != "rail:★" && it != "rail:◇"
            } == true
        }).fetchSemanticsNodes().last().config[SemanticsProperties.TestTag].removePrefix("rail:")
        val labels = rule.onAllNodesWithTag("rail:$section")
        val label = labels[labels.fetchSemanticsNodes().lastIndex].fetchSemanticsNode().boundsInRoot
        val lanes = rule.onAllNodesWithTag("alphabet-hit-lane")
        val lane = lanes[lanes.fetchSemanticsNodes().lastIndex]
        val laneBounds = lane.fetchSemanticsNode().boundsInRoot
        lane.performTouchInput {
            down(Offset(width / 2f, label.center.y - laneBounds.top))
            up()
        }
        waitForAllApps()
        rule.onNodeWithTag("section:$section").assertIsDisplayed()
    }

    @Test fun rotationRestoresAllAppsAndWarmHomeIntentResetsLanding() {
        waitForHome()
        rule.onNodeWithTag("workspace-apps-action").performClick()
        waitForAllApps()
        rule.activityRule.scenario.recreate()
        waitForAllApps()

        val originalIntent = rule.activity.intent
        try {
            rule.runOnIdle {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setClass(rule.activity, MainActivity::class.java)
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(rule.activity, home)
            }
            waitForHome()
            rule.onNodeWithTag("workspace-all-apps").assertIsNotDisplayed()
        } finally {
            rule.runOnIdle { rule.activity.intent = originalIntent }
        }
    }

    @Test fun longStackScrollsBeforeItsBoundaryCanOpenAllApps() {
        waitForHome()
        val store = OrganizationStore(rule.activity)
        val existing = runBlocking { store.currentSnapshot().widgets }
        val placeholders = (1..6).map { index ->
            WorkspaceWidget(1_000_000 + index, "home", "fixture.missing/.Provider", 180)
        }
        try {
            runBlocking {
                val snapshot = store.currentSnapshot()
                store.update(OrganizationBatch(listOf(OrganizationEdit.SetWidgets(existing + placeholders)),
                    snapshot.revision)).getOrThrow()
            }
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("workspace-widget:1000001").fetchSemanticsNodes().isNotEmpty()
            }
            val before = rule.onNodeWithTag("workspace-widget:1000001").fetchSemanticsNode().boundsInRoot.top
            rule.onNodeWithTag("workspace-home").performTouchInput {
                val start = Offset(width / 2f, height * 0.65f)
                down(start)
                moveTo(start - Offset(0f, 140f), 150L)
                up()
            }
            val after = rule.onNodeWithTag("workspace-widget:1000001").fetchSemanticsNode().boundsInRoot.top
            assertTrue("Home stack should consume the first drag ($before -> $after)", after < before - 30f)
            rule.onNodeWithTag("workspace-all-apps").assertIsNotDisplayed()

            // One later pointer gesture reaches the remaining stack end and
            // hands its unused distance to All apps before that pointer lifts.
            rule.onNodeWithTag("workspace-home").performTouchInput {
                val start = Offset(width / 2f, height * 0.7f)
                down(start)
                for (step in 1..20) moveTo(start - Offset(0f, height * 2.1f * step / 20f), 20L)
                up()
            }
            waitForAllApps()
        } finally {
            runBlocking {
                val snapshot = store.currentSnapshot()
                store.update(OrganizationBatch(listOf(OrganizationEdit.SetWidgets(existing)),
                    snapshot.revision)).getOrThrow()
            }
        }
    }

    @Test fun allAppsScrollsBackToItsTopBeforeOneDragReturnsHome() {
        waitForHome()
        val store = OrganizationStore(rule.activity)
        val existing = runBlocking { store.currentSnapshot().widgets }
        val placeholders = (1..6).map { index ->
            WorkspaceWidget(1_100_000 + index, "home", "fixture.missing/.Provider", 180)
        }
        try {
            runBlocking {
                val snapshot = store.currentSnapshot()
                store.update(OrganizationBatch(listOf(OrganizationEdit.SetWidgets(existing + placeholders)),
                    snapshot.revision)).getOrThrow()
            }
            val home = rule.onNodeWithTag("workspace-home")
            home.performScrollToNode(hasTestTag("workspace-edit"))
            val bottomScroll = home.fetchSemanticsNode().config[
                SemanticsProperties.VerticalScrollAxisRange
            ].value()
            assertTrue("Fixture must leave Home scrolled before opening Apps: $bottomScroll", bottomScroll > 100f)
            rule.onNodeWithTag("workspace-apps-action").performClick()
            waitForAllApps()
            val allApps = rule.onNodeWithTag("workspace-all-apps")
            allApps.performTouchInput {
                val start = Offset(width * 0.45f, height * 0.7f)
                down(start)
                for (step in 1..12) moveTo(start - Offset(0f, height * 0.55f * step / 12f), 20L)
                up()
            }
            allApps.assertIsDisplayed()
            allApps.performTouchInput {
                val start = Offset(width * 0.45f, height * 0.35f)
                down(start)
                moveTo(start + Offset(0f, 120f), 160L)
                up()
            }
            allApps.assertIsDisplayed()

            allApps.performTouchInput {
                val start = Offset(width * 0.45f, height * 0.25f)
                down(start)
                for (step in 1..40) moveTo(start + Offset(0f, height * 4f * step / 40f), 20L)
                up()
            }
            waitForHome()
            rule.onNodeWithTag("workspace-all-apps").assertIsNotDisplayed()
            // The same pointer kept moving after Home entered and reduced the
            // saved Home scroll offset; a first-widget visibility threshold is
            // sensitive to how far the app list flung before this drag began.
            val returnedScroll = home.fetchSemanticsNode().config[
                SemanticsProperties.VerticalScrollAxisRange
            ].value()
            assertTrue("Home must scroll during the returning pointer drag: $bottomScroll -> $returnedScroll",
                returnedScroll < bottomScroll - 30f)
        } finally {
            runBlocking {
                val snapshot = store.currentSnapshot()
                store.update(OrganizationBatch(listOf(OrganizationEdit.SetWidgets(existing)),
                    snapshot.revision)).getOrThrow()
            }
        }
    }

    private fun waitForHome() {
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("workspace-home").fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithTag("workspace-apps-action").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("workspace-home").assertIsDisplayed()
    }

    private fun waitForAllApps() {
        rule.waitUntil(15_000) {
            runCatching { rule.onNodeWithTag("workspace-all-apps").assertIsDisplayed() }.isSuccess
        }
    }
}
