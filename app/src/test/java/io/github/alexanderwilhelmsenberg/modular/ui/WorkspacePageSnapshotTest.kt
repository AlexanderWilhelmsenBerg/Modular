package io.github.alexanderwilhelmsenberg.modular.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspacePageSnapshotTest {
    @Test fun passiveListRetainsNamesButNoCommandsOrHandoffs() {
        val live = """{"version":1,"kind":"files","view":"browser","state":"ready",
            "title":"Files","locationId":"private-location","folderId":"private-folder",
            "actions":[{"id":"delete-token","kind":"confirm"}],"selectedIds":["id-1"],
            "dialog":{"title":"Delete?","actions":[{"id":"confirm-token"}]},
            "hostLaunch":{"nonce":"secret-nonce"},"document":{"id":"private-document"},
            "breadcrumbs":[{"name":"Documents","action":{"id":"up-token"}}],
            "items":[{"id":"id-1","name":"private-name.txt","kind":"file",
                "actions":[{"id":"item-token","kind":"trash"}]}]}"""
        val snapshot = WorkspacePageSnapshot.sanitize(live)
        assertNotNull(snapshot)
        val parsed = Json.parseToJsonElement(requireNotNull(snapshot)).jsonObject
        assertEquals("private-name.txt", parsed["items"]!!.jsonArray[0].jsonObject["name"]
            .toString().trim('"'))
        assertEquals("Documents", parsed["breadcrumbs"]!!.jsonArray[0].jsonObject["name"]
            .toString().trim('"'))
        assertFalse("actions" in parsed)
        assertFalse("selectedIds" in parsed)
        assertFalse("dialog" in parsed)
        assertFalse("hostLaunch" in parsed)
        assertFalse("document" in parsed)
        assertFalse("actions" in parsed["items"]!!.jsonArray[0].jsonObject)
        assertFalse("action" in parsed["breadcrumbs"]!!.jsonArray[0].jsonObject)
        assertEquals(snapshot, WorkspacePageSnapshot.parse(snapshot))
    }

    @Test fun privilegedOrOversizedPagesCannotBeRestored() {
        val base = """{"version":1,"kind":"files","view":"%s","state":"ready","items":[]}"""
        listOf("review", "markdown", "pdf", "progress", "movePicker", "restoreReview", "clean")
            .forEach { assertNull(WorkspacePageSnapshot.sanitize(base.format(it))) }
        assertFalse(WorkspacePageSnapshot.mustClear(base.format("clean")))
        assertNull(WorkspacePageSnapshot.sanitize(base.format("browser")
            .replace("\"ready\"", "\"permission\"")))
        listOf("permission", "unavailable", "error").forEach { state ->
            assertTrue(WorkspacePageSnapshot.mustClear(base.format("browser")
                .replace("\"ready\"", "\"$state\"")))
        }
        assertNull(WorkspacePageSnapshot.parse("{" + "x".repeat(128 * 1024) + "}"))
    }
}
