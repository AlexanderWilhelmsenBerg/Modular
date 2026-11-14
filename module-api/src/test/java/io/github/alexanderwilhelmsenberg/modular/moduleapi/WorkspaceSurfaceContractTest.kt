package io.github.alexanderwilhelmsenberg.modular.moduleapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSurfaceContractTest {
    private val valid = """{"version":1,"sessionId":"123e4567-e89b-12d3-a456-426614174000","widthPx":800,"heightPx":600,"displayId":0,"densityDpi":320,"fontScale":1.0,"visible":true,"theme":"dark","colors":{"background":-16777216},"localeTags":"en-US"}"""

    @Test fun acceptsHostAndUnknownOptionalFields() {
        val spec = WorkspaceSurfaceContract.parseHostSpec(valid.dropLast(1) + ",\"future\":true}")
        assertEquals(800, spec.widthPx)
        assertEquals(-16777216, spec.colors["background"])
        assertTrue(spec.visible)
    }

    @Test fun rejectsInvalidBoundsAndIdentity() {
        rejects(valid.replace("\"widthPx\":800", "\"widthPx\":8193"))
        rejects(valid.replace("\"heightPx\":600", "\"heightPx\":0"))
        rejects(valid.replace("\"version\":1", "\"version\":2"))
        rejects(valid.replace("\"sessionId\":\"123e4567-e89b-12d3-a456-426614174000\"", "\"sessionId\":\"bad\""))
        rejects(valid.replace("\"fontScale\":1.0", "\"fontScale\":4.0"))
        rejects(valid.replace("\"densityDpi\":320", "\"densityDpi\":71"))
        rejects(valid.replace("\"visible\":true", "\"visible\":\"true\""))
    }

    @Test fun stateDefaultsToExclusiveGestures() {
        val state = WorkspaceSurfaceContract.parseState("""{"version":1,"canGoBack":false}""")
        assertFalse(state.canGoBack)
        assertTrue(state.exclusiveGestures)
        assertEquals(WorkspaceSurfaceState(true, false), WorkspaceSurfaceContract.parseState(
            WorkspaceSurfaceContract.encodeState(WorkspaceSurfaceState(true, false))))
        rejectsState("""{"version":1,"canGoBack":"true"}""")
    }

    @Test fun navigationAndActivityNonceAreBounded() {
        WorkspaceSurfaceContract.validateNavigationDestination("apps")
        WorkspaceSurfaceContract.validateActivityNonce("a".repeat(32))
        runCatching { WorkspaceSurfaceContract.validateNavigationDestination("file:///secret") }.onSuccess { error("Accepted route") }
        runCatching { WorkspaceSurfaceContract.validateActivityNonce("short") }.onSuccess { error("Accepted nonce") }
    }

    private fun rejects(value: String) {
        runCatching { WorkspaceSurfaceContract.parseHostSpec(value) }.onSuccess { error("Accepted invalid host") }
    }
    private fun rejectsState(value: String) {
        runCatching { WorkspaceSurfaceContract.parseState(value) }.onSuccess { error("Accepted invalid state") }
    }
}
