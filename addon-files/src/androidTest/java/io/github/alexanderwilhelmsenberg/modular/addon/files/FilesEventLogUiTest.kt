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
