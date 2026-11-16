package io.github.alexanderwilhelmsenberg.modular.addon.files

import org.junit.Assert.*
import org.junit.Test

class FilesPageRequestGateTest {
    @Test fun lateReadCannotDismissTheScreenOpenedByAnAction() {
        val gate = FilesPageRequestGate()
        val read = requireNotNull(gate.beginRead())
        val action = gate.beginAction()
        gate.endAction()
        assertFalse(gate.mayApply(read))
        assertTrue(gate.mayApply(action))
    }

    @Test fun pollingWaitsUntilUserCommandsHaveAppliedTheirPages() {
        val gate = FilesPageRequestGate()
        val action = gate.beginAction()
        assertNull(gate.beginRead())
        gate.endAction()
        assertEquals(action, gate.beginRead())
    }

    @Test fun olderCommandResponsesCannotOverwriteNewerNavigation() {
        val gate = FilesPageRequestGate()
        val first = gate.beginAction()
        val second = gate.beginAction()
        gate.endAction()
        assertNull(gate.beginRead())
        gate.endAction()
        assertFalse(gate.mayApply(first))
        assertTrue(gate.mayApply(second))
    }
}
