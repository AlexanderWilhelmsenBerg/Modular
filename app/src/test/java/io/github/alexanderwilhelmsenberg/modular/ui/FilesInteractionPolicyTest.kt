package io.github.alexanderwilhelmsenberg.modular.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesInteractionPolicyTest {
    @Test fun cleanupKeepsGesturesThroughoutPreparationKeeperAndReview() {
        listOf("clean", "progress", "keeper", "cleanReview").forEach { view ->
            assertTrue("Cleanup must own gestures in $view", filesPageOwnsGestures(view, false, false))
        }
    }

    @Test fun ordinaryBrowsingReturnsGesturesOnlyAfterSelectionAndDialogAreClosed() {
        assertFalse(filesPageOwnsGestures("browser", false, false))
        assertTrue(filesPageOwnsGestures("browser", true, false))
        assertTrue(filesPageOwnsGestures("browser", false, true))
        assertFalse(filesPageOwnsGestures("overview", false, false))
    }
}
