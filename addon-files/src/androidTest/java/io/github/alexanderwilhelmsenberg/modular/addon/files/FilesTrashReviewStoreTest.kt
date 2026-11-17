package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.EmptyTrashPlan
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FileEntry
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.TrashEntry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class FilesTrashReviewStoreTest {
    private fun fixture() = File(ApplicationProvider.getApplicationContext<Context>().cacheDir,
        "trash-review-${UUID.randomUUID()}.json")

    @Test fun pendingAndCompletePlansSurviveStoreRecreationIncludingUnknownMetadata() {
        val file = fixture()
        try {
            val store = TrashReviewStore(file)
            val pending = store.begin(listOf("one", "one"), 2)
            assertEquals(listOf("one"), TrashReviewStore(file).load()!!.ids)
            assertNull(TrashReviewStore(file).load()!!.plan)
            val record = TrashEntry("one", "root", "original", "parent", "file", "folder/file",
                123, null, null, null, false, true)
            val entry = FileEntry("payload", "root", null, "file", "trash/file", false,
                null, null, null, null, true, true)
            val plan = EmptyTrashPlan(listOf(record), listOf(entry), true, emptyList(),
                mapOf("payload" to "identity-v1:object:metadata"))
            store.complete(pending.id, plan)
            val restored = TrashReviewStore(file).load()!!
            assertEquals(pending.id, restored.id)
            assertEquals(2, restored.offline)
            assertEquals(plan, restored.plan)
        } finally { TrashReviewStore(file).clear() }
    }

    @Test fun lateCompletionCannotRestoreCanceledOrReplacedRequests() {
        val file = fixture()
        try {
            val store = TrashReviewStore(file)
            val old = store.begin(listOf("one"), 0)
            store.clear(old.id)
            val plan = EmptyTrashPlan(emptyList(), emptyList(), true, emptyList(), emptyMap())
            store.complete(old.id, plan)
            assertNull(TrashReviewStore(file).load())
            val current = store.begin(listOf("two"), 1)
            store.complete(old.id, plan)
            store.clear(old.id)
            assertEquals(current, TrashReviewStore(file).load())
        } finally { TrashReviewStore(file).clear() }
    }

    @Test fun truncatedStateCannotBecomeADeletionPlan() {
        val file = fixture()
        try {
            file.writeText("{\"version\":1,")
            val store = TrashReviewStore(file)
            assertNull(store.load())
            assertNotNull(store.error)
        } finally { TrashReviewStore(file).clear() }
    }
}
