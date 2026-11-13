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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the real page command protocol across the 100-row Binder page boundary. */
@RunWith(AndroidJUnit4::class)
class FilesReviewPaginationTest {
    @Test fun blockedBatchReturnsNamedConflictAndSingleFindingReviewStaysIndependent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("files-page", 0).edit().clear()
            .putStringSet("cleanExcludedIds", setOf("opaque-blocked-base")).commit()
        val repository = ReviewFixtureRepository(itemCount = 0)
        fun file(id: String, name: String, modified: Long) = FileEntry(id, "test-volume", "root",
            name, "root/$name", false, "text/plain", 12L, modified, null, true, true)
        repository.files.add(file("opaque-blocked-base", "report.txt", 1))
        repository.files.add(file("opaque-conflict-one", "report (1).txt", 2))
        repository.files.add(file("opaque-conflict-two", "report (2).txt", 3))
        repository.files.add(file("opaque-safe-base", "safe.txt", 1))
        repository.files.add(file("opaque-safe-copy", "safe (1).txt", 2))
        val controller = FilesPageController(context, repository)
        try {
            controller.addedLocation("test-volume")
            val setup = action(controller, page(controller), "Clean")
            action(controller, setup, "Start scan")
            val results = awaitView(controller, "clean")
            assertEquals(2, results.getJSONArray("items").length())
            assertEquals(0, results.getInt("cleanSelectionCount"))
            assertFalse(results.getBoolean("cleanCanReview"))
            assertEquals(0, repository.fingerprintCalls)
            val conflict = (0 until results.getJSONArray("items").length())
                .map { results.getJSONArray("items").getJSONObject(it) }
                .single { it.getString("name") == "report.txt" }
            val selectedConflict = rowAction(controller, conflict, "Include")
            val safe = (0 until selectedConflict.getJSONArray("items").length())
                .map { selectedConflict.getJSONArray("items").getJSONObject(it) }
                .single { it.getString("name") == "safe.txt" }
            val selectedBoth = rowAction(controller, safe, "Include")
            assertEquals(2, selectedBoth.getInt("cleanSelectionCount"))
            action(controller, selectedBoth, "Review selected")
            awaitCondition { page(controller).getString("view") == "clean" }
            val blocked = page(controller)
            assertEquals("clean", blocked.getString("view"))
            val reason = blocked.getString("message")
            assertTrue(reason.contains("report.txt"))
            assertTrue(reason.contains("rename", ignoreCase = true) || reason.contains("exist", ignoreCase = true))
            assertFalse(reason.contains("fingerprint", ignoreCase = true))
            assertFalse(reason.contains("revision-pending"))
            assertFalse(reason.contains("opaque-"))
            assertEquals(0, repository.fingerprintCalls)
            assertEquals(0, repository.moveCalls)

            val cleared = action(controller, blocked, "Clear selection")
            assertEquals(0, cleared.getInt("cleanSelectionCount"))
            val safeOnly = (0 until cleared.getJSONArray("items").length())
                .map { cleared.getJSONArray("items").getJSONObject(it) }
                .single { it.getString("name") == "safe.txt" }
            val focused = rowAction(controller, safeOnly, "Review")
            assertEquals("progress", focused.getString("view"))
            val review = awaitView(controller, "cleanReview")
            val ids = (0 until review.getJSONArray("items").length())
                .map { review.getJSONArray("items").getJSONObject(it).getString("id") }.toSet()
            assertEquals(setOf("opaque-safe-base", "opaque-safe-copy"), ids)
            assertTrue(repository.fingerprintCalls > 0)
            assertEquals(0, repository.moveCalls)
            val canceled = action(controller, review, "Cancel")
            assertEquals("clean", canceled.getString("view"))
            assertEquals(0, repository.moveCalls)
        } finally {
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    @Test fun unknownSizesInNumberedFoldersRemainNullableThroughKeeperAndReview() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("files-page", 0).edit().clear().commit()
        val repository = ReviewFixtureRepository(itemCount = 0)
        fun fixture(id: String, parent: String, name: String, directory: Boolean) = FileEntry(
            id, "test-volume", parent, name, "$parent/$name", directory,
            if (directory) null else "text/plain", null, 1, null, true, true)
        repository.files.add(fixture("kept", "root", "archive", true))
        repository.files.add(fixture("removed", "root", "archive (1)", true))
        repository.files.add(fixture("kept-file", "kept", "notes.txt", false))
        repository.files.add(fixture("removed-file", "removed", "notes.txt", false))
        val controller = FilesPageController(context, repository)
        try {
            controller.addedLocation("test-volume")
            val setup = action(controller, page(controller), "Clean")
            action(controller, setup, "Start scan")
            val results = awaitView(controller, "clean")
            val group = results.getJSONArray("items").getJSONObject(0)
            assertEquals("group", group.getString("kind"))
            val keeperPage = rowAction(controller, group, "Review")
            assertEquals("keeper", keeperPage.getString("view"))
            val candidates = keeperPage.getJSONArray("items")
            assertEquals(2, candidates.length())
            for (index in 0 until candidates.length()) assertUnknownSize(candidates.getJSONObject(index))
            val kept = (0 until candidates.length()).map { candidates.getJSONObject(it) }
                .single { it.getString("id") == "kept" }
            val preparing = rowAction(controller, kept, "Keep this")
            assertEquals("progress", preparing.getString("view"))
            val review = awaitView(controller, "cleanReview")
            val reviewed = review.getJSONArray("items")
            assertEquals(setOf("kept", "removed", "kept-file", "removed-file"),
                (0 until reviewed.length()).map { reviewed.getJSONObject(it).getString("id") }.toSet())
            for (index in 0 until reviewed.length()) assertUnknownSize(reviewed.getJSONObject(index))
            assertTrue(review.getString("summary").contains("unknown-size files"))
            assertEquals(0, repository.moveCalls)

            val back = action(controller, review, "Back")
            assertEquals("clean", back.getString("view"))
            action(controller, back, "Review selected")
            val secondReview = awaitView(controller, "cleanReview")
            val canceled = action(controller, secondReview, "Cancel")
            assertEquals("clean", canceled.getString("view"))
            assertEquals(0, repository.moveCalls)
        } finally {
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    @Test fun choosingAnotherKeeperUpdatesTheReclaimableSpaceEstimate() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("files-page", 0).edit().clear().commit()
        val repository = ReviewFixtureRepository(itemCount = 2)
        repository.files[0] = repository.files[0].copy(name = "report.txt", sizeBytes = 1024L * 1024,
            modifiedAtMillis = 1)
        repository.files[1] = repository.files[1].copy(name = "report (1).txt", sizeBytes = 4L * 1024 * 1024,
            modifiedAtMillis = 2)
        val controller = FilesPageController(context, repository)
        try {
            controller.addedLocation("test-volume")
            val setup = action(controller, page(controller), "Clean")
            action(controller, setup, "Start scan")
            val results = awaitView(controller, "clean")
            val group = results.getJSONArray("items").getJSONObject(0)
            assertEquals(1024L * 1024, group.getLong("reclaimableKnownBytes"))
            val keepers = rowAction(controller, group, "Choose keeper").getJSONArray("items")
            val smaller = (0 until keepers.length()).map { keepers.getJSONObject(it) }
                .single { it.getString("id") == repository.files[0].id }
            val changed = rowAction(controller, smaller, "Keep this")
            assertEquals(4L * 1024 * 1024,
                changed.getJSONArray("items").getJSONObject(0).getLong("reclaimableKnownBytes"))
            assertEquals(0, repository.fingerprintCalls)
            assertEquals(0, repository.moveCalls)
        } finally {
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    @Test fun cleanItemSortUsesGroupMemberCount() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("files-page", 0).edit().clear().commit()
        val repository = ReviewFixtureRepository(itemCount = 0)
        fun file(id: String, name: String) = FileEntry(id, "test-volume", "root",
            name, "root/$name", false, "text/plain", 12L, 1, null, true, true)
        repository.files.addAll(listOf(
            file("two-base", "alpha.txt"), file("two-copy", "alpha (1).txt"),
            file("three-base", "zeta.txt"), file("three-copy-1", "zeta (1).txt"),
            file("three-copy-2", "zeta (2).txt")))
        val controller = FilesPageController(context, repository)
        try {
            controller.addedLocation("test-volume")
            val setup = action(controller, page(controller), "Clean")
            action(controller, setup, "Start scan")
            val results = awaitView(controller, "clean")
            assertEquals(2, results.getInt("findingCount"))
            val byItems = action(controller, results, "Sort findings", JSONObject()
                .put("sort", "items").put("direction", "desc"))
            assertEquals("zeta.txt", byItems.getJSONArray("items").getJSONObject(0).getString("name"))
            assertEquals(3, byItems.getJSONArray("items").getJSONObject(0).getInt("itemCount"))
            assertEquals(2, byItems.getJSONArray("items").getJSONObject(1).getInt("itemCount"))
            assertEquals(0, repository.moveCalls)
        } finally {
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    @Test fun largeFileFindingsRankByKnownBytesAndFingerprintOnlyAfterReview() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("files-page", 0).edit().clear().putInt("cleanLargeMiB", 1).commit()
        val repository = ReviewFixtureRepository(itemCount = 3, largeFiles = true)
        repository.files[0] = repository.files[0].copy(sizeBytes = 4L * 1024 * 1024)
        repository.files[1] = repository.files[1].copy(sizeBytes = 3L * 1024 * 1024)
        val controller = FilesPageController(context, repository)
        try {
            controller.addedLocation("test-volume")
            val setup = action(controller, page(controller), "Clean")
            val chosen = toggleCleanCheck(controller, setup, "LARGE_FILE")
            action(controller, chosen, "Start scan")
            val results = awaitView(controller, "clean")
            val items = results.getJSONArray("items")
            assertEquals(3, items.length())
            assertEquals(listOf(4L, 3L, 2L).map { it * 1024 * 1024 },
                (0 until items.length()).map { items.getJSONObject(it).getLong("reclaimableKnownBytes") })
            (0 until items.length()).forEach { index ->
                assertEquals(3, items.getJSONObject(index).getInt("priorityTier"))
                assertEquals("Large file", items.getJSONObject(index).getString("priorityLabel"))
            }
            assertEquals(0, repository.fingerprintCalls)
            val included = rowAction(controller, items.getJSONObject(0), "Include")
            val preparing = action(controller, included, "Review selected")
            assertEquals("progress", preparing.getString("view"))
            awaitView(controller, "cleanReview")
            assertTrue(repository.fingerprintCalls > 0)
            assertEquals(0, repository.moveCalls)
        } finally {
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    @Test fun cleanSortAndSelectAllCoverFindingsBeyondFirstPage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("files-page", 0).edit().clear().putInt("cleanLargeMiB", 1).commit()
        val repository = ReviewFixtureRepository(itemCount = 105, largeFiles = true)
        repository.files.indices.forEach { index ->
            repository.files[index] = repository.files[index].copy(sizeBytes = (index + 2L) * 1024 * 1024)
        }
        val controller = FilesPageController(context, repository)
        try {
            controller.addedLocation("test-volume")
            val setup = action(controller, page(controller), "Clean")
            val checks = toggleCleanCheck(controller, setup, "LARGE_FILE")
            action(controller, checks, "Start scan")
            val results = awaitView(controller, "clean")
            assertEquals("priority", results.getString("cleanSort"))
            assertEquals(105, results.getInt("findingCount"))

            val sorted = action(controller, results, "Sort findings", JSONObject()
                .put("sort", "size").put("direction", "asc"))
            assertEquals("item-0", sorted.getJSONArray("items").getJSONObject(0).getString("name"))
            assertEquals("size", sorted.getString("cleanSort"))
            assertFalse(sorted.getBoolean("cleanSortDescending"))
            val rest = action(controller, sorted, "Load more")
            assertEquals(5, rest.getJSONArray("items").length())
            assertEquals("item-100", rest.getJSONArray("items").getJSONObject(0).getString("name"))
            val all = action(controller, rest, "Select all")
            assertEquals(105, all.getInt("cleanSelectionCount"))
            assertTrue(all.getBoolean("cleanCanReview"))
            assertTrue((0 until all.getJSONArray("items").length()).all {
                all.getJSONArray("items").getJSONObject(it).getBoolean("included")
            })
            assertEquals(0, repository.fingerprintCalls)
            assertEquals(0, repository.moveCalls)

            val descending = action(controller, all, "Sort findings", JSONObject()
                .put("sort", "size").put("direction", "desc"))
            assertEquals("item-104", descending.getJSONArray("items").getJSONObject(0).getString("name"))
            assertTrue(descending.getBoolean("cleanSortDescending"))
            assertEquals(105, descending.getInt("cleanSelectionCount"))
            val byName = action(controller, descending, "Sort findings", JSONObject()
                .put("sort", "name").put("direction", "asc"))
            assertEquals("item-0", byName.getJSONArray("items").getJSONObject(0).getString("name"))
            val byItems = action(controller, byName, "Sort findings", JSONObject()
                .put("sort", "items").put("direction", "desc"))
            assertEquals("items", byItems.getString("cleanSort"))
            assertTrue(context.getSharedPreferences("files-page", 0).getBoolean("cleanSortDescending", false))

            val cleared = action(controller, byItems, "Clear selection")
            assertEquals(0, cleared.getInt("cleanSelectionCount"))
            assertFalse(cleared.getJSONArray("items").getJSONObject(0).getBoolean("included"))
            val included = rowAction(controller, cleared.getJSONArray("items").getJSONObject(0), "Include",
                JSONObject().put("included", true))
            assertEquals(1, included.getInt("cleanSelectionCount"))
            val stillIncluded = rowAction(controller, included.getJSONArray("items").getJSONObject(0), "Included",
                JSONObject().put("included", true))
            assertEquals(1, stillIncluded.getInt("cleanSelectionCount"))
        } finally {
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    @Test fun completedCleanReviewRemainsReadyThroughPageRefreshAndPagination() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("files-page", 0).edit().clear().putInt("cleanLargeMiB", 1).commit()
        val repository = ReviewFixtureRepository(itemCount = 105, largeFiles = true)
        val controller = FilesPageController(context, repository)
        try {
            controller.addedLocation("test-volume")
            val setup = action(controller, page(controller), "Clean")
            val checks = toggleCleanCheck(controller, setup, "LARGE_FILE")
            action(controller, checks, "Start scan")
            val results = awaitView(controller, "clean")
            val all = action(controller, results, "Select all")
            val preparing = action(controller, all, "Review selected")
            assertEquals("progress", preparing.getString("view"))
            val review = awaitView(controller, "cleanReview")
            assertTrue(review.getBoolean("complete"))
            assertEquals(100, review.getJSONArray("items").length())
            assertEquals("100", review.getString("nextCursor"))
            repeat(3) {
                val refreshed = page(controller)
                assertEquals("cleanReview", refreshed.getString("view"))
                assertTrue(refreshed.getBoolean("complete"))
                assertEquals(0, repository.moveCalls)
            }
            val remaining = action(controller, page(controller), "Load more")
            assertEquals("cleanReview", remaining.getString("view"))
            assertTrue(remaining.getBoolean("complete"))
            assertEquals(5, remaining.getJSONArray("items").length())
            assertTrue(remaining.isNull("nextCursor"))
            assertEquals("cleanReview", page(controller).getString("view"))
            assertEquals(0, repository.moveCalls)
        } finally {
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    @Test fun cleanSetupDefaultsToNumberedCopiesAndPersistsChosenChecksWithoutScanning() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("files-page", 0).edit().clear().commit()
        val repository = ReviewFixtureRepository(itemCount = 0)
        try {
            val controller = FilesPageController(context, repository)
            controller.addedLocation("test-volume")
            val setup = action(controller, page(controller), "Clean")
            assertEquals("clean", setup.getString("view"))
            assertTrue(setup.getBoolean("cleanSetup"))
            assertTrue(setup.getBoolean("cleanCanScan"))
            val ids = listOf("NUMBERED_COPY", "EXACT_DUPLICATE", "OLDER_VERSION", "LARGE_FILE",
                "OLD_DOWNLOAD", "DOWNLOADED_INSTALLER_OR_ARCHIVE")
            assertEquals(ids.toSet(), (0 until setup.getJSONArray("cleanChecks").length())
                .map { setup.getJSONArray("cleanChecks").getJSONObject(it).getString("id") }.toSet())
            ids.forEach { id -> assertEquals(id == "NUMBERED_COPY", cleanCheck(setup, id).getBoolean("enabled")) }
            assertEquals(0, repository.scanCalls)
            assertEquals(0, repository.fingerprintCalls)

            val none = toggleCleanCheck(controller, setup, "NUMBERED_COPY")
            assertFalse(none.getBoolean("cleanCanScan"))
            assertEquals(0, repository.scanCalls)
            val rejected = action(controller, none, "Start scan")
            assertTrue(rejected.getBoolean("cleanSetup"))
            assertTrue(rejected.getString("message").contains("Choose at least one check"))
            assertEquals(0, repository.scanCalls)
            val chosen = toggleCleanCheck(controller, rejected, "LARGE_FILE")
            assertTrue(chosen.getBoolean("cleanCanScan"))
            assertFalse(cleanCheck(chosen, "OLD_DOWNLOAD").getBoolean("enabled"))

            val restored = FilesPageController(context, repository)
            restored.addedLocation("test-volume")
            val reopened = action(restored, page(restored), "Clean")
            assertTrue(cleanCheck(reopened, "LARGE_FILE").getBoolean("enabled"))
            assertFalse(cleanCheck(reopened, "NUMBERED_COPY").getBoolean("enabled"))
            assertFalse(cleanCheck(reopened, "OLD_DOWNLOAD").getBoolean("enabled"))
            assertEquals(0, repository.scanCalls)
            assertEquals(0, repository.fingerprintCalls)
        } finally {
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    @Test fun detailsRefreshesChildSizesWithoutExpiringBrowserActions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("files-page", 0).edit().clear().commit()
        val repository = ReviewFixtureRepository(itemCount = 0, includeSubfolder = true)
        repository.parentTotalCached = true
        val release = CountDownLatch(1)
        repository.sizeRelease = release
        val controller = FilesPageController(context, repository)
        try {
            controller.addedLocation("test-volume")
            val details = action(controller, page(controller), "Details view")
            assertTrue(details.getBoolean("sizesCalculating"))
            assertEquals("details", details.getString("layout"))
            assertTrue(repository.sizeEntered.await(5, TimeUnit.SECONDS))
            val newFileId = (0 until details.getJSONArray("actions").length())
                .map { details.getJSONArray("actions").getJSONObject(it) }
                .single { it.getString("label") == "New file" }.getString("id")
            val pollingId = (0 until details.getJSONArray("actions").length())
                .map { details.getJSONArray("actions").getJSONObject(it) }
                .single { it.getString("kind") == "sizeProgress" }.getString("id")
            fun poll(): JSONObject = JSONObject(controller.performAction(JSONObject().put("version", 1)
                .put("actionId", pollingId).put("operationToken", UUID.randomUUID().toString()).toString()))
            val whileCalculating = poll()
            assertEquals(newFileId, (0 until whileCalculating.getJSONArray("actions").length())
                .map { whileCalculating.getJSONArray("actions").getJSONObject(it) }
                .single { it.getString("label") == "New file" }.getString("id"))
            assertEquals(0, repository.createdFiles.size)
            release.countDown()
            awaitCondition { !poll().getBoolean("sizesCalculating") }
            val completed = poll()
            assertEquals(21L, completed.getJSONArray("items").getJSONObject(0).getLong("sizeBytes"))
            assertEquals(1, repository.sizeWalks)
            assertEquals(newFileId, (0 until completed.getJSONArray("actions").length())
                .map { completed.getJSONArray("actions").getJSONObject(it) }
                .single { it.getString("label") == "New file" }.getString("id"))
            val invalidCreate = JSONObject(controller.performAction(JSONObject().put("version", 1)
                .put("actionId", newFileId).put("operationToken", UUID.randomUUID().toString())
                .put("args", JSONObject().put("name", "").put("extension", "txt")).toString()))
            assertTrue(invalidCreate.getString("message").contains("Invalid name"))
            assertEquals(0, repository.createdFiles.size)
        } finally {
            release.countDown()
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    @Test fun reopeningScanAfterChangingFolderAndSettingsKeepsCapturedScope() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("files-page", 0).edit().clear().putInt("cleanLargeMiB", 1).commit()
        val repository = ReviewFixtureRepository(itemCount = 105, largeFiles = true, includeSubfolder = true)
        val release = CountDownLatch(1)
        repository.scanRelease = release
        val controller = FilesPageController(context, repository)
        try {
            controller.addedLocation("test-volume")
            val setup = action(controller, page(controller), "Clean")
            val withLargeFiles = toggleCleanCheck(controller, setup, "LARGE_FILE")
            action(controller, withLargeFiles, "Start scan")
            assertTrue(repository.scanSecondPage.await(5, TimeUnit.SECONDS))
            val browser = action(controller, page(controller), "Back to files")
            val settings = action(controller, browser, "Settings")
            val changed = action(controller, settings, "Large file threshold (MiB)",
                JSONObject().put("value", "3"))
            val back = action(controller, changed, "Back")
            val subfolder = back.getJSONArray("items").getJSONObject(0)
            assertEquals("subfolder", subfolder.getString("id"))
            val opened = rowAction(controller, subfolder, "Open")
            assertEquals("subfolder", opened.getString("folderId"))
            val reopened = action(controller, opened, "Clean")
            assertEquals("progress", reopened.getString("view"))
            assertEquals("root", reopened.getString("cleanScopeId"))
            assertEquals("subfolder", reopened.getString("folderId"))
            assertEquals(1, repository.scanCalls)
            release.countDown()
            val completed = awaitView(controller, "clean")
            assertEquals("root", completed.getString("cleanScopeId"))
            assertEquals("subfolder", completed.getString("folderId"))
            assertTrue(completed.getJSONArray("items").length() > 0)
            assertTrue(completed.getJSONArray("items").getJSONObject(0).has("priorityTier"))
            val returned = action(controller, completed, "Back to folder")
            assertEquals("subfolder", returned.getString("folderId"))
            assertEquals("browser", returned.getString("view"))
        } finally {
            release.countDown()
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    @Test fun cleanProgressSurvivesCloseAndReopenWithoutStartingAnotherScan() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("files-page", 0).edit().clear().putInt("cleanLargeMiB", 1).commit()
        val repository = ReviewFixtureRepository(itemCount = 105, largeFiles = true)
        val release = CountDownLatch(1)
        repository.scanRelease = release
        val controller = FilesPageController(context, repository)
        try {
            controller.addedLocation("test-volume")
            val setup = action(controller, page(controller), "Clean")
            val withLargeFiles = toggleCleanCheck(controller, setup, "LARGE_FILE")
            val progress = action(controller, withLargeFiles, "Start scan")
            assertEquals("progress", progress.getString("view"))
            assertTrue(repository.scanSecondPage.await(5, TimeUnit.SECONDS))
            val partial = page(controller)
            assertEquals("progress", partial.getString("view"))
            assertTrue(partial.getBoolean("cleanScanRunning"))
            assertFalse(partial.getBoolean("complete"))
            assertTrue(partial.getInt("filesScanned") > 0)
            assertEquals(1, repository.scanCalls)

            val refreshed = action(controller, partial, "Refresh progress")
            val hidden = action(controller, refreshed, "Back to files")
            assertEquals("browser", hidden.getString("view"))
            assertTrue(hidden.getBoolean("cleanScanRunning"))
            val reopened = action(controller, hidden, "Clean")
            assertEquals("progress", reopened.getString("view"))
            assertEquals(1, repository.scanCalls)
            action(controller, reopened, "Back to files")
            release.countDown()
            awaitCondition { !page(controller).getBoolean("cleanScanRunning") }
            val afterCompletion = page(controller)
            assertEquals("browser", afterCompletion.getString("view"))
            val completed = action(controller, afterCompletion, "Clean")
            assertEquals("clean", completed.getString("view"))
            assertEquals(1, repository.scanCalls)

            val cancelRelease = CountDownLatch(1)
            repository.scanSecondPage = CountDownLatch(1)
            repository.scanRelease = cancelRelease
            val secondProgress = action(controller, completed, "Scan again")
            assertEquals("progress", secondProgress.getString("view"))
            assertTrue(repository.scanSecondPage.await(5, TimeUnit.SECONDS))
            val canceled = action(controller, page(controller), "Cancel operation")
            assertEquals("browser", canceled.getString("view"))
            cancelRelease.countDown()
            awaitCondition { !page(controller).getBoolean("cleanScanRunning") }
            assertEquals("browser", page(controller).getString("view"))
            assertEquals(2, repository.scanCalls)
        } finally {
            release.countDown()
            repository.scanRelease?.countDown()
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    @Test fun newTextFileValidatesExtensionAndNeverOverwritesCollision() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("files-page", 0).edit().clear().commit()
        val repository = ReviewFixtureRepository(itemCount = 0)
        val controller = FilesPageController(context, repository)
        try {
            controller.addedLocation("test-volume")
            var browser = action(controller, page(controller), "New file",
                JSONObject().put("name", "notes.txt").put("extension", "txt"))
            assertEquals(listOf("notes.txt" to "text/plain"), repository.createdFiles)
            browser = action(controller, browser, "New file",
                JSONObject().put("name", "README").put("extension", "md"))
            assertEquals("README.md" to "text/markdown", repository.createdFiles.last())
            browser = action(controller, browser, "New file",
                JSONObject().put("name", "plain").put("extension", ""))
            assertEquals("plain" to "text/plain", repository.createdFiles.last())
            for ((name, extension) in listOf("escape" to "../txt", "escape" to "x/y",
                "../escape" to "txt", "notes.txt" to "txt")) {
                browser = action(controller, browser, "New file",
                    JSONObject().put("name", name).put("extension", extension))
                assertEquals(3, repository.createdFiles.size)
            }
            assertEquals(1, repository.files.count { it.name == "notes.txt" })
        } finally {
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    @Test fun scrollUpdatesCannotExpireCleanOrReplaceItsScreenWithAnError() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("files-page", 0).edit().clear().commit()
        val repository = ReviewFixtureRepository(itemCount = 0)
        val controller = FilesPageController(context, repository)
        try {
            controller.addedLocation("test-volume")
            val browser = page(controller)
            val oldScroll = (0 until browser.getJSONArray("actions").length())
                .map { browser.getJSONArray("actions").getJSONObject(it) }
                .single { it.getString("kind") == "scroll" }
            val oldScrollRequest = JSONObject().put("version", 1)
                .put("actionId", oldScroll.getString("id"))
                .put("operationToken", UUID.randomUUID().toString())
                .put("args", JSONObject().put("anchor", 2).put("offset", 17)).toString()

            // A delayed scroll write before the tap must leave the original Clean ID usable.
            val afterScroll = JSONObject(controller.performAction(oldScrollRequest))
            assertEquals("browser", afterScroll.getString("view"))
            assertEquals(2, afterScroll.getInt("scrollAnchor"))
            controller.recordScrollPosition("wrong-folder", 99, 999)
            assertEquals(2, page(controller).getInt("scrollAnchor"))
            controller.recordScrollPosition(browser.getString("scrollKey"), 3, 19)
            val cleanId = (0 until browser.getJSONArray("actions").length())
                .map { browser.getJSONArray("actions").getJSONObject(it) }
                .single { it.getString("kind") == "clean" }.getString("id")
            val scanning = JSONObject(controller.performAction(JSONObject().put("version", 1)
                .put("actionId", cleanId).put("operationToken", UUID.randomUUID().toString())
                .put("restoreScroll", JSONObject().put("key", browser.getString("scrollKey"))
                    .put("anchor", 7).put("offset", 41)).toString()))
            assertEquals("clean", scanning.getString("view"))
            assertTrue(scanning.getBoolean("cleanSetup"))

            // A browser disposal write and a poll may arrive after Clean has changed routes.
            val polled = page(controller)
            assertEquals("clean", polled.getString("view"))
            val afterLateScroll = JSONObject(controller.performAction(oldScrollRequest))
            assertEquals("clean", afterLateScroll.getString("view"))
            assertFalse(afterLateScroll.optString("state") == "error")
            action(controller, afterLateScroll, "Start scan")
            val recommendations = awaitView(controller, "clean")
            assertEquals("screen", recommendations.getString("presentation"))
            val afterAnotherLateScroll = JSONObject(controller.performAction(oldScrollRequest))
            assertEquals("clean", afterAnotherLateScroll.getString("view"))
            assertFalse(afterAnotherLateScroll.optString("message").contains("Action expired"))
            val returned = action(controller, afterAnotherLateScroll, "Back to folder")
            assertEquals("browser", returned.getString("view"))
            assertEquals(7, returned.getInt("scrollAnchor"))
            assertEquals(41, returned.getInt("scrollOffset"))
            assertEquals(0, repository.moveCalls)
        } finally {
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    @Test fun cleanWaitsForExplicitStartAndClosingScreenKeepsCurrentFolder() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("files-page", 0).edit().clear().commit()
        val repository = ReviewFixtureRepository(itemCount = 0)
        val controller = FilesPageController(context, repository)
        try {
            controller.addedLocation("test-volume")
            val browser = page(controller)
            assertEquals("browser", browser.getString("view"))
            assertEquals("root", browser.getString("folderId"))

            val setup = action(controller, browser, "Clean")
            assertEquals("clean", setup.getString("view"))
            assertTrue(setup.getBoolean("cleanSetup"))
            assertEquals(0, repository.scanCalls)
            assertEquals(0, repository.fingerprintCalls)
            val scanning = action(controller, setup, "Start scan")
            assertEquals("progress", scanning.getString("view"))
            assertEquals("screen", scanning.getString("presentation"))
            assertEquals("clean", scanning.getString("progressFor"))

            val recommendations = awaitView(controller, "clean")
            assertEquals("screen", recommendations.getString("presentation"))
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

            val staleDeleteId = (0 until rest.getJSONArray("actions").length())
                .map { rest.getJSONArray("actions").getJSONObject(it) }
                .single { it.getString("label") == "Delete" }.getString("id")

            val canceled = action(controller, rest, "Cancel")
            assertEquals("search", canceled.getString("view"))
            assertEquals(0, repository.moveCalls)

            val staleDelete = JSONObject(controller.performAction(JSONObject().put("version", 1)
                .put("actionId", staleDeleteId).put("operationToken", UUID.randomUUID().toString()).toString()))
            assertEquals("search", staleDelete.getString("view"))
            assertEquals("An expired destructive ID must not move files", 0, repository.moveCalls)

            val secondPreparing = action(controller, staleDelete, "Delete all results")
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

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue("Timed out waiting for condition", condition())
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

    private fun rowAction(controller: FilesPageController, row: JSONObject, label: String,
        args: JSONObject? = null): JSONObject {
        val actions = row.getJSONArray("actions")
        val command = (0 until actions.length()).map { actions.getJSONObject(it) }
            .single { it.getString("label") == label }
        val request = JSONObject().put("version", 1)
            .put("actionId", command.getString("id"))
            .put("operationToken", UUID.randomUUID().toString())
        if (args != null) request.put("args", args)
        return JSONObject(controller.performAction(request.toString()))
    }

    private fun cleanCheck(page: JSONObject, id: String): JSONObject =
        (0 until page.getJSONArray("cleanChecks").length())
            .map { page.getJSONArray("cleanChecks").getJSONObject(it) }
            .single { it.getString("id") == id }

    private fun assertUnknownSize(item: JSONObject) {
        assertTrue("Expected nullable size for ${item.optString("id")}",
            !item.has("sizeBytes") || item.isNull("sizeBytes"))
    }

    private fun toggleCleanCheck(controller: FilesPageController, from: JSONObject, id: String): JSONObject {
        val command = cleanCheck(from, id).getJSONObject("action")
        return JSONObject(controller.performAction(JSONObject().put("version", 1)
            .put("actionId", command.getString("id"))
            .put("operationToken", UUID.randomUUID().toString()).toString()))
    }

    private class ReviewFixtureRepository(itemCount: Int = 105, private val largeFiles: Boolean = false,
        includeSubfolder: Boolean = false) : FilesRepository {
        private val root = entry("root", true)
        val files = (0 until itemCount).map { entry("item-$it", false) }.toMutableList().also {
            if (includeSubfolder) it.add(0, entry("subfolder", true).copy(parentId = "root"))
        }
        var scanRelease: CountDownLatch? = null
        var scanSecondPage = CountDownLatch(1)
        var scanCalls = 0
        var fingerprintCalls = 0
        val createdFiles = mutableListOf<Pair<String, String>>()
        var parentTotalCached = false
        var childTotalCached = false
        var sizeRelease: CountDownLatch? = null
        val sizeEntered = CountDownLatch(1)
        var sizeWalks = 0
        var moveCalls = 0
        var lastPlanSize = 0

        override fun locations() = listOf(StorageLocation("test-volume", "Fixture", "root",
            StorageLocation.Kind.SELECTED_FOLDER, true, true, 1_000_000, 500_000))
        override fun addSelectedFolder(uri: Uri, grantFlags: Int): StorageLocation = locations().single()
        override fun removeSelectedFolder(locationId: String) = Unit
        override fun list(folderId: String, offset: Int, limit: Int, sort: FileSort,
            showHidden: Boolean): FilePage {
            val children = files.filter { it.parentId == folderId }
            if (Thread.currentThread().name == "files-worker") {
                if (offset == 0 && folderId == "root") scanCalls++
                if (offset == 100 && scanRelease != null) {
                    scanSecondPage.countDown()
                    check(scanRelease!!.await(10, TimeUnit.SECONDS)) { "Scan fixture timed out" }
                }
            }
            return FilePage(children.drop(offset).take(limit),
                (offset + limit).takeIf { it < children.size }, children.size, true)
        }
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
        override fun directoryTotal(folderId: String, cancel: Cancellation): DirectoryTotal {
            if (folderId == "root" && sizeRelease != null) {
                sizeWalks++
                sizeEntered.countDown()
                check(sizeRelease!!.await(10, TimeUnit.SECONDS)) { "Size fixture timed out" }
                childTotalCached = true
            }
            return DirectoryTotal(42, 1, true, 1)
        }
        override fun cachedDirectoryTotal(folderId: String): DirectoryTotal? = when {
            folderId == "root" && parentTotalCached -> DirectoryTotal(42, 1, true, 1)
            folderId == "subfolder" && childTotalCached -> DirectoryTotal(21, 1, true, 1)
            else -> null
        }
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
        override fun fingerprint(id: String): String {
            fingerprintCalls++
            return "fixture:$id"
        }
        override fun scopedContent(id: String): ScopedContent = error("Unexpected content grant")
        override fun createDirectory(parentId: String, name: String): FileEntry = error("Unexpected create")
        override fun createFile(parentId: String, name: String, mimeType: String): FileEntry {
            require(files.none { it.name == name }) { "Name collision" }
            createdFiles.add(name to mimeType)
            return entry(name, false).also(files::add)
        }
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
            if (folder) null else if (largeFiles) 2L * 1024 * 1024 else 1L, 1, null, true, true)
    }
}
