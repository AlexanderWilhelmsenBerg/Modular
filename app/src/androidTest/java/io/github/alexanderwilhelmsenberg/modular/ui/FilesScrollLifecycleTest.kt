package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.alexanderwilhelmsenberg.modular.filesui.FilesPage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FilesScrollLifecycleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun cleanAndLifecycleFlushesNeverSubmitAnExpiredBrowserAction() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        val key = "a".repeat(64)
        fun action(id: String, kind: String, label: String) = JSONObject()
            .put("id", id).put("kind", kind).put("label", label)
        val browser = JSONObject().put("version", 1).put("kind", "files")
            .put("view", "browser").put("state", "ready").put("title", "Downloads")
            .put("folderId", "downloads").put("locationId", "phone").put("scrollKey", key)
            .put("items", JSONArray()).put("actions", JSONArray()
                .put(action("clean", "clean", "Clean"))
                .put(action("old-scroll", "scroll", "Remember position"))).toString()
        val clean = JSONObject().put("version", 1).put("kind", "files")
            .put("view", "clean").put("state", "ready").put("title", "Clean folder")
            .put("folderId", "downloads").put("locationId", "phone")
            .put("presentation", "sheet").put("underlyingView", "browser")
            .put("items", JSONArray()).put("actions", JSONArray()).toString()
        var page by mutableStateOf(browser)
        var visible by mutableStateOf(true)
        val requests = mutableListOf<String>()
        val savedKeys = mutableListOf<String>()
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (visible) FilesPage(pageJson = page, onAction = {
                        val id = JSONObject(it).getString("actionId")
                        requests.add(id)
                        page = if (id == "clean") clean else JSONObject()
                            .put("version", 1).put("kind", "files").put("view", "error")
                            .put("title", "Action expired").toString()
                    }, onOpenDocument = { _, _ -> }, onScrollState = { savedKey, _, _ -> savedKeys.add(savedKey) })
                }
            }
        }
        rule.onNodeWithText("Clean").performClick()
        rule.onNodeWithTag("files.clean.sheet").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(listOf("clean"), requests)
            assertTrue("Leaving the browser must preserve its position", key in savedKeys)
            page = browser
        }
        rule.waitForIdle()
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.runOnIdle { visible = false }
        rule.runOnIdle {
            assertEquals("Stop/disposal must not submit a file action", listOf("clean"), requests)
            assertTrue(savedKeys.all { it == key })
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
