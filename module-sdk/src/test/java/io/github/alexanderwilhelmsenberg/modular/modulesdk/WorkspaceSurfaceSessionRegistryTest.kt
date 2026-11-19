package io.github.alexanderwilhelmsenberg.modular.modulesdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceSurfaceSessionRegistryTest {
    @Test fun limitAllowsReplacementOnlyAfterTeardown() {
        val sessions = WorkspaceSurfaceSessionRegistry<String>(2)
        sessions.add("first")
        sessions.add("second")
        assertThrows(IllegalStateException::class.java) { sessions.add("third") }
        sessions.remove("first")
        sessions.add("third")
        assertEquals(listOf("second", "third"), sessions.closeAndDrain())
    }

    @Test fun revocationAtomicallyRejectsNewSessions() {
        val sessions = WorkspaceSurfaceSessionRegistry<String>(2)
        sessions.add("first")
        assertEquals(listOf("first"), sessions.closeAndDrain())
        assertEquals(emptyList<String>(), sessions.closeAndDrain())
        assertThrows(IllegalStateException::class.java) { sessions.add("late") }
    }
}
