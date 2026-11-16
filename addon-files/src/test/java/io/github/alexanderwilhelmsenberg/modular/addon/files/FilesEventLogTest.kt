package io.github.alexanderwilhelmsenberg.modular.addon.files

import org.junit.Assert.*
import org.junit.Test

class FilesEventLogTest {
    @Test fun failuresAndOperationIdsSurviveRestartAndCanBeCleared() {
        var persisted: String? = null
        val log = FilesEventLog({ persisted }, { persisted = it; true }, { 123L })
        log.record("operation.failed", "Empty trash failed", "ERROR", "operation-one",
            IllegalStateException("Drive unavailable"))
        val restored = FilesEventLog({ persisted }, { persisted = it; true })
        val entry = restored.snapshot().single()
        assertEquals("ERROR", entry.getString("level"))
        assertEquals("operation-one", entry.getString("operationId"))
        assertTrue(restored.export().contains("Drive unavailable"))
        restored.clear()
        assertTrue(FilesEventLog({ persisted }, { true }).snapshot().isEmpty())
    }

    @Test fun logIsBoundedAndKeepsTheMostRecentFailure() {
        var persisted: String? = null
        val log = FilesEventLog({ persisted }, { persisted = it; true })
        repeat(250) { log.record("progress", "item $it", details = "x".repeat(8000)) }
        log.record("operation.failed", "Last failure", "ERROR")
        assertTrue(log.snapshot().size <= 200)
        assertTrue(requireNotNull(persisted).toByteArray().size <= 192_000)
        assertEquals("Last failure", log.snapshot().first().getString("message"))
        assertTrue(log.export().length <= 32_000)
    }

    @Test fun failedPersistenceDoesNotLoseTheInMemoryDiagnostic() {
        val log = FilesEventLog({ "corrupt" }, { false })
        log.record("operation.failed", "Storage offline", "ERROR")
        assertEquals(1, log.snapshot().size)
        assertNotNull(log.storageError)
    }
}
