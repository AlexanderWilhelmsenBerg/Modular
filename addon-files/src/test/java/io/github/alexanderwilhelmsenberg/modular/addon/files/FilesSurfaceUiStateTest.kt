package io.github.alexanderwilhelmsenberg.modular.addon.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesSurfaceUiStateTest {
    @Test fun ordinaryBrowserAllowsHostNavigationAndAppsBoundaryGesture() {
        val state = filesSurfaceUiState("""{"view":"browser","actions":[{"kind":"back","id":"up"}],"selectedIds":[]}""")
        assertEquals("up", state.backActionId)
        assertFalse(state.exclusiveGestures)
        assertTrue(state.allowAppsSwipe)
    }

    @Test fun selectionsDialogsAndProtectedViewsKeepFilesGestureControl() {
        val selected = filesSurfaceUiState("""{"view":"browser","selectedIds":["file"]}""")
        val dialog = filesSurfaceUiState("""{"view":"overview","dialog":{"title":"Review"}}""")
        assertTrue(selected.exclusiveGestures)
        assertFalse(selected.allowAppsSwipe)
        assertTrue(dialog.exclusiveGestures)
        assertFalse(dialog.allowAppsSwipe)
        for (view in listOf("review", "markdown", "pdf", "clean", "cleanReview", "progress", "keeper")) {
            val state = filesSurfaceUiState("""{"view":"$view"}""")
            assertTrue("$view should retain Files gestures", state.exclusiveGestures)
            assertFalse(state.allowAppsSwipe)
        }
    }

    @Test fun unknownPageIsConservative() {
        assertTrue(filesSurfaceUiState(null).exclusiveGestures)
        assertFalse(filesSurfaceUiState("invalid").allowAppsSwipe)
    }
}
