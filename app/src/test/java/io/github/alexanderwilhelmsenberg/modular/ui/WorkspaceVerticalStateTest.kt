package io.github.alexanderwilhelmsenberg.modular.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceVerticalStateTest {
    private val height = 600f

    @Test fun boundaryRemainderOpensWithinTheSamePointerDrag() {
        val state = WorkspaceVerticalState()
        // The stack consumes the first 180 px. Its remaining motion is handed
        // to the adjacent All apps surface before this pointer is released.
        val drag = -500f
        val stackConsumed = -180f
        val surfaceConsumed = state.drag(drag - stackConsumed, height)

        assertEquals(-320f, surfaceConsumed, 0.001f)
        assertEquals(320f / height, state.progress, 0.001f)
        assertTrue(state.releaseTarget())
    }

    @Test fun reverseMotionAndCancellationReturnToOrigin() {
        val state = WorkspaceVerticalState()
        assertEquals(-210f, state.drag(-210f, height), 0.001f)
        assertEquals(160f, state.drag(160f, height), 0.001f)
        assertFalse(state.releaseTarget())
        state.snap(open = false)
        assertEquals(0f, state.progress, 0.001f)
        assertEquals(0f, state.drag(100f, height), 0.001f)
    }

    @Test fun downwardBoundaryReversesFromAllAppsAndClampsAtHome() {
        val state = WorkspaceVerticalState(initiallyOpen = true)
        // All apps has reached its first row; the remaining drag moves Home
        // down from above rather than moving the app rows further.
        assertEquals(360f, state.drag(360f, height), 0.001f)
        assertEquals(0.4f, state.progress, 0.001f)
        assertFalse(state.releaseTarget())
        assertEquals(240f, state.drag(400f, height), 0.001f)
        assertEquals(0f, state.progress, 0.001f)
    }

    @Test fun flingVelocityWithoutActiveBoundaryDragCannotChangeScreen() {
        val home = WorkspaceVerticalState()
        assertFalse(home.releaseTarget(velocityY = -4000f))
        assertEquals(0f, home.progress, 0.001f)

        val allApps = WorkspaceVerticalState(initiallyOpen = true)
        assertTrue(allApps.releaseTarget(velocityY = 4000f))
        assertEquals(1f, allApps.progress, 0.001f)
    }

    @Test fun velocityCanSettleAnAlreadyStartedCrossing() {
        val state = WorkspaceVerticalState()
        state.drag(-90f, height)
        assertFalse(state.releaseTarget())
        assertTrue(state.releaseTarget(velocityY = -1200f))
        assertFalse(state.releaseTarget(velocityY = 1200f))
    }
}
