package io.github.alexanderwilhelmsenberg.modular.modulesdk

import org.junit.Assert.*
import org.junit.Test

class WorkspaceSurfaceCleanupTest {
    @Test fun androidAlreadyReleasedHostCannotEscapeTeardown() {
        val calls = mutableListOf<String>()
        val failure = NullPointerException("ViewRootImpl.die on null")
        cleanupWorkspaceSurface({ calls += "scope" }, { calls += "owner" }, { throw failure }) { stage, error ->
            assertEquals("host", stage); assertSame(failure, error); calls += "reported"
        }
        assertEquals(listOf("scope", "owner", "reported"), calls)
    }

    @Test fun failingOwnerStillReleasesTheHost() {
        val calls = mutableListOf<String>()
        cleanupWorkspaceSurface({ throw IllegalStateException("scope callback") },
            { throw IllegalStateException("owner callback") }, { calls += "host" }) { stage, _ -> calls += stage }
        assertEquals(listOf("scope", "owner", "host"), calls)
    }

    @Test fun fatalErrorsAreNotSuppressed() {
        assertThrows(OutOfMemoryError::class.java) {
            cleanupWorkspaceSurface({}, {}, { throw OutOfMemoryError("fatal") }) { _, _ -> fail() }
        }
    }
}
