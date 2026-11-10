package io.github.alexanderwilhelmsenberg.modular.moduleapi

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class WorkspacePageContractTest {
    @Test fun onlyVersionedBoundedFilesPagesReachNativeRenderer() {
        assertEquals("modular.workspace.page/v1", StandardCapabilities.WorkspacePage.wireValue)
        WorkspacePageContract.validateFilesPage("""{"version":1,"kind":"files","items":[]}""")
        WorkspacePageContract.validatePageRequest("""{"version":1,"pageSize":100,"cursor":null}""")
        rejects { WorkspacePageContract.validatePageRequest("""{"version":1,"pageSize":101}""") }
        rejects { WorkspacePageContract.validateFilesPage("""{"version":2,"kind":"files"}""") }
        rejects { WorkspacePageContract.validateFilesPage("""{"version":1,"kind":"web"}""") }
        val oversizedPage = """{"version":1,"kind":"files","items":[""" +
            List(WorkspacePageContract.MAX_PAGE_SIZE + 1) { "{}" }.joinToString(",") + "]}"
        rejects { WorkspacePageContract.validateFilesPage(oversizedPage) }
        rejects { WorkspacePageContract.validateFilesPage("""{"version":1,"kind":"files","mapNodes":{}}""") }
    }

    @Test fun actionsRequireOpaqueIdentityAndTokenWithinRequestLimit() {
        WorkspacePageContract.validateActionRequest(
            """{"version":1,"actionId":"a1","operationToken":"123e4567-e89b-12d3-a456-426614174000","args":{"name":"new"}}""",
        )
        rejects { WorkspacePageContract.validateActionRequest("""{"version":1,"operationToken":"123e4567-e89b-12d3-a456-426614174000"}""") }
        rejects { WorkspacePageContract.validateActionRequest("""{"version":1,"actionId":"a1"}""") }
        rejects { WorkspacePageContract.validateActionRequest("""{"version":1,"actionId":"a1","operationToken":"123e4567-e89b-12d3-a456-426614174000","args":[]}""") }
        rejects { WorkspacePageContract.validateActionRequest("""{"version":1,"actionId":"a1","operationToken":"123e4567-e89b-12d3-a456-426614174000","args":{"text":"${"x".repeat(WorkspacePageContract.MAX_REQUEST_BYTES)}"}}""") }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid page message to be rejected") }
        catch (_: IllegalArgumentException) { }
    }
}
