package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.alexanderwilhelmsenberg.modular.filesui.FilesPage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FilesEventLogUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun notificationPromptDispatchesAndCrashCopyKeepsTheFullStack() {
        val requests = mutableListOf<String>()
        val state = mutableStateOf(JSONObject().put("version", 1).put("kind", "files").put("view", "progress")
            .put("items", JSONArray()).put("actions", JSONArray())
            .put("activeOperation", JSONObject().put("id", "scan").put("label", "Scanning folder").put("status", "Inspecting APK"))
            .put("progressNotificationAction", JSONObject().put("id", "notifications").put("kind", "notifications")
                .put("label", "Show progress notifications")).toString())
        rule.setContent { MaterialTheme { FilesPage(state.value, onAction = {
            requests.add(JSONObject(it).getString("actionId"))
        }, onOpenDocument = { _, _ -> }) } }
        rule.onNodeWithTag("files.progress.notifications").performClick()
        rule.runOnIdle { assertEquals(listOf("notifications"), requests) }
        val stack = "java.lang.IllegalStateException: full crash\n" + "at example.Frame.method(Frame.kt:1)\n".repeat(350)
        rule.runOnIdle { state.value = JSONObject().put("version", 1).put("kind", "files").put("view", "eventLog")
            .put("items", JSONArray()).put("actions", JSONArray()).put("crashReportText", stack).toString() }
        rule.onNodeWithTag("files.events.copyCrash").performClick()
        rule.runOnIdle {
            val context = ApplicationProvider.getApplicationContext<Context>()
            assertEquals(stack, context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    @Test fun reviewIssueActionsUseTheFullScreenAndDispatchImmediately() {
        val requests = mutableListOf<String>()
        val issue = JSONObject().put("message", "A file already occupies the rename destination")
            .put("actions", JSONArray().put(JSONObject().put("id", "choose").put("kind", "resolveKeeper")
                .put("label", "Choose keeper")).put(JSONObject().put("id", "exclude")
                .put("kind", "excludeGroup").put("label", "Exclude group")))
        val page = JSONObject().put("version", 1).put("kind", "files").put("view", "clean")
            .put("cleanIssues", JSONArray().put(issue)).put("cleanIssueCount", 1)
            .put("items", JSONArray()).put("actions", JSONArray())
        rule.setContent { MaterialTheme { FilesPage(page.toString(), onAction = {
            requests.add(JSONObject(it).getString("actionId"))
        }, onOpenDocument = { _, _ -> }) } }
        for ((kind, id) in listOf("resolveKeeper" to "choose", "excludeGroup" to "exclude")) {
            rule.onNodeWithTag("files.clean.showIssues").performClick()
            rule.onNodeWithTag("files.clean.issues.dialog").assertIsDisplayed()
            rule.onNodeWithTag("files.clean.issue.$kind").performClick()
            rule.runOnIdle { assertEquals(id, requests.last()) }
            rule.onNodeWithTag("files.clean.issues.dialog").assertDoesNotExist()
        }
    }

    @Test fun operationShowsOneTitleAndLiveProgressAndLogHasDownload() {
        val label = "Moving reviewed cleanup files to trash"
        val progress = JSONObject().put("version", 1).put("kind", "files").put("view", "progress")
            .put("summary", label).put("title", "Files").put("state", "loading")
            .put("activeOperation", JSONObject().put("id", "job").put("label", label).put("status", label)
                .put("doneBytes", 5).put("totalBytes", 10).put("elapsedMillis", 2_000))
            .put("items", JSONArray()).put("actions", JSONArray())
        val state = mutableStateOf(progress.toString())
        val requests = mutableListOf<String>()
        rule.setContent { MaterialTheme { FilesPage(state.value, onAction = {
            requests.add(JSONObject(it).getString("actionId"))
        }, onOpenDocument = { _, _ -> }) } }
        rule.onAllNodesWithText(label).assertCountEquals(1)
        rule.onNodeWithTag("files.operation.progress").assertIsDisplayed()
        rule.onNodeWithText("50%", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Loading…").assertDoesNotExist()
        rule.runOnIdle {
            state.value = JSONObject().put("version", 1).put("kind", "files").put("view", "eventLog")
                .put("items", JSONArray()).put("actions", JSONArray().put(JSONObject().put("id", "download")
                    .put("kind", "downloadEventLog").put("label", "Download JSON")).put(JSONObject().put("id", "share")
                    .put("kind", "shareEventLog").put("label", "Share JSON"))).toString()
        }
        rule.onNodeWithTag("files.events.download").performClick()
        rule.onNodeWithTag("files.events.share").performClick()
        rule.runOnIdle { assertEquals(listOf("download", "share"), requests) }
    }

    @Test fun keeperChoiceShowsDatesAndCanBeSelectedDirectly() {
        val requests = mutableListOf<String>()
        val item = JSONObject().put("id", "copy").put("name", "report (1).txt").put("kind", "candidate")
            .put("modifiedAt", 123L).put("sizeBytes", 12L).put("actions", JSONArray().put(
                JSONObject().put("id", "choose").put("kind", "keeper").put("label", "Keep this")))
        val page = JSONObject().put("version", 1).put("kind", "files").put("view", "keeper")
            .put("title", "Choose a copy to keep").put("keeperGroupName", "report.txt")
            .put("keeperChoicesRemaining", 2).put("cleanSelectionCount", 3)
            .put("items", JSONArray().put(item)).put("actions", JSONArray())
        rule.setContent { MaterialTheme { FilesPage(page.toString(), onAction = {
            requests.add(JSONObject(it).getString("actionId"))
        }, onOpenDocument = { _, _ -> }) } }
        rule.onNodeWithText("2 choices remaining · 3 findings selected").assertIsDisplayed()
        rule.onNodeWithText("Modified", substring = true).assertIsDisplayed()
        rule.onNodeWithText("report (1).txt").performClick()
        rule.runOnIdle { assertEquals(listOf("choose"), requests) }
    }

    @Test fun backgroundPollingUsesReadOnlyRefreshInsteadOfAnOldAction() {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        var actions = 0
        val page = """{"version":1,"kind":"files","view":"progress","actions":[{"id":"old","kind":"refresh","label":"Refresh"}],"items":[]}"""
        rule.setContent { MaterialTheme { FilesPage(page, onAction = { actions++ },
            onOpenDocument = { _, _ -> }, onRefresh = { reads.incrementAndGet() }) } }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitUntil(5_000) { reads.get() > 0 }
        rule.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun eventDetailsCopyAndBackWorkWithoutOpeningAnotherApp() {
        val detail = JSONObject().put("id", "failure").put("at", 123).put("level", "ERROR")
            .put("event", "operation.failed").put("message", "Trash review failed")
            .put("details", "Drive unavailable").put("actions", JSONArray().put(
                JSONObject().put("id", "open").put("kind", "open").put("label", "Details")))
        val base = JSONObject().put("version", 1).put("kind", "files").put("view", "eventLog")
            .put("eventLogText", "Drive unavailable").put("items", JSONArray().put(detail))
            .put("actions", JSONArray().put(JSONObject().put("id", "back").put("kind", "back").put("label", "Back")))
        val state = mutableStateOf(base.toString())
        rule.setContent { MaterialTheme { FilesPage(state.value, onAction = { request ->
            val next = JSONObject(base.toString())
            if (JSONObject(request).getString("actionId") == "open") next.put("view", "eventDetail").put("eventDetail", detail)
            state.value = next.toString()
        }, onOpenDocument = { _, _ -> }) } }
        rule.onNodeWithText("Trash review failed").performClick()
        rule.onNodeWithText("Drive unavailable").assertIsDisplayed()
        rule.onNodeWithTag("files.events.copy").performClick()
        rule.runOnIdle {
            val context = ApplicationProvider.getApplicationContext<Context>()
            assertEquals("Drive unavailable", context.getSystemService(ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
        }
        rule.onNodeWithText("Back").performClick()
        rule.onNodeWithText("Trash review failed").assertIsDisplayed()
    }
}
