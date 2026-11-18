package io.github.alexanderwilhelmsenberg.modular.addon.files

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner.CleanerEngine as C
import io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner.CleanerRepositoryAdapter
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class CleanScanStoreTest {
    private fun file() = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "scan-${UUID.randomUUID()}.json")
    private fun options() = C.Options("root", false, setOf("excluded"), emptySet(), 1024, 90,
        Instant.ofEpochMilli(123), setOf(C.Category.OLDER_VERSION))
    private fun entry() = C.Entry("one", "phone", "root", "folder/one.apk", "one.apk", C.Kind.FILE,
        null, null, null, C.PENDING_REVISION, true, C.ApkEvidence("package", "signer", 2,
            null, "arm64-v8a", null, "base", true))
    @Test fun pendingCheckpointAndCompletedFindingsSurviveStoreRecreation() {
        val file = file()
        try {
            val store = CleanScanStore(file)
            val request = store.begin("Ø Downloads", options())
            assertEquals(request, CleanScanStore(file).load())
            val snapshot = CleanerRepositoryAdapter.Snapshot(listOf(entry()), listOf("partial folder"))
            store.checkpoint(request.id, snapshot)
            assertEquals(snapshot, CleanScanStore(file).load()!!.snapshot)
            val analysis = C.Analysis(mapOf("one" to entry()), listOf(C.Group("group", C.Category.OLDER_VERSION,
                listOf("one", "two"), "one", false, "one.apk", "version evidence")),
                listOf(C.Suggestion("one", C.Category.LARGE_FILE, "optional", false)), listOf("warning"), options())
            store.complete(request.id, analysis)
            val saved = CleanScanStore(file).load()!!
            assertEquals(analysis, saved.analysis)
            assertNull(saved.snapshot)
            assertEquals(0, saved.attempts)
        } finally { CleanScanStore(file).clear() }
    }
    @Test fun canceledAndReplacedRequestsCannotBeResurrectedByLateWrites() {
        val file = file()
        try {
            val store = CleanScanStore(file)
            val old = store.begin("old", options())
            store.clear(old.id)
            store.checkpoint(old.id, CleanerRepositoryAdapter.Snapshot(emptyList(), emptyList()))
            assertNull(CleanScanStore(file).load())
            val current = store.begin("current", options())
            store.clear(old.id)
            store.complete(old.id, C.Analysis(emptyMap(), emptyList(), emptyList(), emptyList(), options()))
            assertEquals(current, CleanScanStore(file).load())
        } finally { CleanScanStore(file).clear() }
    }
    @Test fun recoveryAttemptsPersistAndCorruptRequestsDoNotBecomeWork() {
        val file = file()
        try {
            var store = CleanScanStore(file)
            store.begin("pending", options())
            repeat(3) { store.recovering(store.load()!!); store = CleanScanStore(file) }
            assertEquals(3, store.load()!!.attempts)
            file.writeText("{\"version\":1,")
            assertNull(CleanScanStore(file).load())
        } finally { CleanScanStore(file).clear() }
    }
}
