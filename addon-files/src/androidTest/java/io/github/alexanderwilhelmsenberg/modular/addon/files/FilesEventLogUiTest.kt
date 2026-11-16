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
