package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The safe reconnect display survives a new store instance and is bound to the module signer. */
@RunWith(AndroidJUnit4::class)
class WorkspacePageSnapshotPersistenceTest {
    @Test fun snapshotRoundTripsAndSignerChangePurgesOldDisplay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = "validation:${UUID.randomUUID()}"
        val firstSigner = "validation-signer-a"
        val secondSigner = "validation-signer-b"
        val live = """{"version":1,"kind":"files","view":"browser","state":"ready",
            "title":"Saved folder","locationId":"volume-a","folderId":"folder-a",
            "currentFolder":{"id":"folder-a","name":"Saved folder","icon":"music","color":"#6795D4"},
            "actions":[{"id":"delete-token","kind":"confirm"}],
            "items":[{"id":"file-a","name":"track.mp3","kind":"file",
                "actions":[{"id":"item-delete-token","kind":"confirm"}]}]}"""
        try {
            WorkspacePageSnapshotStore(context).save(component, firstSigner, live)
            val restored = WorkspacePageSnapshotStore(context).restore(component, firstSigner)
            val page = JSONObject(requireNotNull(restored))
            assertEquals("browser", page.getString("view"))
            assertEquals("folder-a", page.getString("folderId"))
            assertEquals("music", page.getJSONObject("currentFolder").getString("icon"))
            assertEquals("track.mp3", page.getJSONArray("items").getJSONObject(0).getString("name"))
            assertFalse(page.has("actions"))
            assertFalse(page.getJSONArray("items").getJSONObject(0).has("actions"))

            assertNull(WorkspacePageSnapshotStore(context).restore(component, secondSigner))
            assertNull(WorkspacePageSnapshotStore(context).restore(component, firstSigner))
            WorkspacePageSnapshotStore(context).save(component, secondSigner, live)
            assertTrue(WorkspacePageSnapshotStore(context).restore(component, secondSigner) != null)
        } finally {
            WorkspacePageSnapshotStore(context).purge(component)
        }
        assertNull(WorkspacePageSnapshotStore(context).restore(component, secondSigner))
    }
}
