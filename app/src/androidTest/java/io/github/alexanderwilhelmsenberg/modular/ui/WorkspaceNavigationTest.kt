package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationStore
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
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
            rule.onNodeWithTag("workspace-home").performScrollToNode(hasTestTag("workspace-widget:1100006"))
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
                for (step in 1..30) moveTo(start + Offset(0f, height * 2.6f * step / 30f), 20L)
                up()
            }
            waitForHome()
            rule.onNodeWithTag("workspace-all-apps").assertIsNotDisplayed()
            // The same pointer kept moving after Home entered and scrolled its
            // saved stack back toward the first widget.
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("workspace-widget:1100001").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("workspace-widget:1100001").assertIsDisplayed()
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
