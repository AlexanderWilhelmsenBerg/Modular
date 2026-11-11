package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.Cancellation
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.CollisionChoice
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.DeletionPlan
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.DirectoryTotal
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.EmptyTrashPlan
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FileEntry
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FileOutcome
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FilePage
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FileSort
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FilesRepository
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.OperationResult
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.OperationHistoryEntry
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.Progress
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.RestoreChoice
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.ScopedContent
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.SearchFilter
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.SearchPage
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.StagedWrite
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.StorageLocation
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.TrashEntry
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.TrashSummary
import org.json.JSONObject
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the real page command protocol across the 100-row Binder page boundary. */
@RunWith(AndroidJUnit4::class)
class FilesReviewPaginationTest {
    @Test fun cleanStartsImmediatelyAndClosingSheetKeepsCurrentFolder() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("files-page", 0).edit().clear().commit()
        val repository = ReviewFixtureRepository(itemCount = 0)
        val controller = FilesPageController(context, repository)
        try {
            controller.addedLocation("test-volume")
            val browser = page(controller)
            assertEquals("browser", browser.getString("view"))
            assertEquals("root", browser.getString("folderId"))

            val scanning = action(controller, browser, "Clean")
            assertEquals("progress", scanning.getString("view"))
            assertEquals("sheet", scanning.getString("presentation"))
            assertEquals("browser", scanning.getString("underlyingView"))
            assertEquals("clean", scanning.getString("progressFor"))

            val recommendations = awaitView(controller, "clean")
            assertEquals("sheet", recommendations.getString("presentation"))
            assertEquals("browser", recommendations.getString("underlyingView"))
            assertTrue(context.getSharedPreferences("files-page", 0)
                .getLong("lastCleanScanAt:root", 0) > 0)
            assertEquals(0, repository.moveCalls)

            val returned = action(controller, recommendations, "Back to folder")
            assertEquals("browser", returned.getString("view"))
            assertEquals("root", returned.getString("folderId"))
            assertEquals(0, repository.moveCalls)
        } finally {
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    @Test fun everyReviewedFileCanBeReachedAndCancelIsInert() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("files-page", 0).edit().clear().commit()
        val repository = ReviewFixtureRepository()
        val controller = FilesPageController(context, repository)
        try {
            controller.addedLocation("test-volume")
            val browser = page(controller)
            val search = action(controller, browser, "Search", JSONObject().put("query", "item"))
            assertEquals("search", search.getString("view"))
            val preparing = action(controller, search, "Delete all results")
            assertEquals("progress", preparing.getString("view"))
            val review = awaitView(controller, "review")
            assertEquals("review", review.getString("view"))
            assertTrue(review.getBoolean("complete"))
            assertEquals(100, review.getJSONArray("items").length())
            assertEquals("100", review.getString("nextCursor"))

            val rest = action(controller, review, "Load more")
            assertEquals("review", rest.getString("view"))
            assertEquals(5, rest.getJSONArray("items").length())
            assertEquals("item-100", rest.getJSONArray("items").getJSONObject(0).getString("name"))
            assertEquals("item-104", rest.getJSONArray("items").getJSONObject(4).getString("name"))
            assertEquals(0, repository.moveCalls)

            val canceled = action(controller, rest, "Cancel")
            assertEquals("search", canceled.getString("view"))
            assertEquals(0, repository.moveCalls)

            val secondPreparing = action(controller, canceled, "Delete all results")
            assertEquals("progress", secondPreparing.getString("view"))
            val secondReview = awaitView(controller, "review")
            val confirm = (0 until secondReview.getJSONArray("actions").length())
                .map { secondReview.getJSONArray("actions").getJSONObject(it) }
                .single { it.getString("label") == "Delete" }
            val confirmRequest = JSONObject().put("version", 1)
                .put("actionId", confirm.getString("id"))
                .put("operationToken", UUID.randomUUID().toString()).toString()
            val deleting = JSONObject(controller.performAction(confirmRequest))
            assertEquals("progress", deleting.getString("view"))
            val deleted = awaitView(controller, "search")
            assertEquals("search", deleted.getString("view"))
            assertEquals(1, repository.moveCalls)
            assertEquals(105, repository.lastPlanSize)
            assertFalse(deleted.optString("message").contains("failed", ignoreCase = true))
            controller.performAction(confirmRequest)
            assertEquals("A duplicate Binder delivery must not re-run deletion", 1, repository.moveCalls)
        } finally {
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    private fun page(controller: FilesPageController): JSONObject = JSONObject(
        controller.getPage("""{"version":1,"pageSize":100}"""),
    )

    private fun awaitView(controller: FilesPageController, expected: String): JSONObject {
        val deadline = System.currentTimeMillis() + 10_000
        var last = page(controller)
        while (last.optString("view") != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
            last = page(controller)
        }
        assertEquals("Timed out waiting for $expected; last page=$last", expected, last.optString("view"))
        return last
    }

    private fun action(controller: FilesPageController, from: JSONObject, label: String,
        args: JSONObject? = null, token: String = UUID.randomUUID().toString()): JSONObject {
        val actions = from.getJSONArray("actions")
        val command = (0 until actions.length()).map { actions.getJSONObject(it) }
            .single { it.getString("label") == label }
        val request = JSONObject().put("version", 1).put("actionId", command.getString("id"))
            .put("operationToken", token)
        if (args != null) request.put("args", args)
        return JSONObject(controller.performAction(request.toString()))
    }

    private class ReviewFixtureRepository(itemCount: Int = 105) : FilesRepository {
        private val root = entry("root", true)
        private val files = (0 until itemCount).map { entry("item-$it", false) }
        var moveCalls = 0
        var lastPlanSize = 0

        override fun locations() = listOf(StorageLocation("test-volume", "Fixture", "root",
            StorageLocation.Kind.SELECTED_FOLDER, true, true, 1_000_000, 500_000))
        override fun addSelectedFolder(uri: Uri, grantFlags: Int): StorageLocation = locations().single()
        override fun removeSelectedFolder(locationId: String) = Unit
        override fun list(folderId: String, offset: Int, limit: Int, sort: FileSort,
            showHidden: Boolean): FilePage = FilePage(files.drop(offset).take(limit),
            (offset + limit).takeIf { it < files.size }, files.size, true)
        override fun get(id: String): FileEntry = if (id == "root") root else files.single { it.id == id }
        override fun search(folderIds: List<String>, filter: SearchFilter,
            cancel: Cancellation): SearchPage = SearchPage(files.filter { it.name.contains(filter.nameQuery) },
            listOf("test-volume"), true, emptyList())
        override fun searchPage(folderIds: List<String>, filter: SearchFilter, offset: Int,
            limit: Int, cancel: Cancellation): SearchPage {
            val matches = files.filter { it.name.contains(filter.nameQuery) }
            return SearchPage(matches.drop(offset).take(limit), listOf("test-volume"), true,
                emptyList(), (offset + limit).takeIf { it < matches.size }, matches.size)
        }
        override fun directoryTotal(folderId: String, cancel: Cancellation) =
            DirectoryTotal(105, 105, true, 1)
        override fun cachedDirectoryTotal(folderId: String): DirectoryTotal? = null
        override fun prepareDeletion(ids: List<String>, cancel: Cancellation): DeletionPlan {
            val selected = files.filter { it.id in ids }
            return DeletionPlan("fixture-plan", selected, selected, selected.size.toLong(),
                selected.size.toLong(), true, emptyList(), emptyMap(), emptyMap())
        }
        override fun moveToTrash(plan: DeletionPlan, cancel: Cancellation, progress: Progress): OperationResult {
            moveCalls++
            lastPlanSize = plan.descendants.size
            return OperationResult("fixture-delete", plan.roots.map {
                FileOutcome(it.id, FileOutcome.Status.SUCCESS)
            })
        }
        override fun trashSummary() = TrashSummary(0, 0, 0, 0)
        override fun trashEntries(): List<TrashEntry> = emptyList()
        override fun openRead(id: String): ParcelFileDescriptor = error("Unexpected read")
        override fun openWrite(id: String, expectedFingerprint: String): ParcelFileDescriptor = error("Unexpected write")
        override fun beginStagedWrite(id: String, expectedFingerprint: String): StagedWrite = error("Unexpected staged write")
        override fun commitStagedWrite(token: String): FileEntry = error("Unexpected staged commit")
        override fun cancelStagedWrite(token: String) = Unit
        override fun fingerprint(id: String) = error("Unexpected fingerprint")
        override fun scopedContent(id: String): ScopedContent = error("Unexpected content grant")
        override fun createDirectory(parentId: String, name: String): FileEntry = error("Unexpected create")
        override fun createFile(parentId: String, name: String, mimeType: String): FileEntry = error("Unexpected create")
        override fun rename(id: String, newName: String): FileEntry = error("Unexpected rename")
        override fun copyOrMove(ids: List<String>, destinationId: String, move: Boolean,
            collision: CollisionChoice, cancel: Cancellation, progress: Progress,
            replacementPlans: Map<String, DeletionPlan>): OperationResult = error("Unexpected copy")
        override fun restore(ids: List<String>, choices: Map<String, RestoreChoice>,
            cancel: Cancellation): OperationResult = error("Unexpected restore")
        override fun prepareEmptyTrash(ids: List<String>): EmptyTrashPlan = error("Unexpected empty")
        override fun emptyTrash(plan: EmptyTrashPlan, cancel: Cancellation): OperationResult = error("Unexpected empty")
        override fun history(): List<OperationHistoryEntry> = emptyList()
        override fun clearHistory() = Unit
        override fun undoLast(): OperationResult = error("Unexpected undo")

        private fun entry(name: String, folder: Boolean) = FileEntry(name, "test-volume",
            if (folder) null else "root", name, name, folder, if (folder) null else "text/plain",
            if (folder) null else 1, 1, null, true, true)
    }
}
