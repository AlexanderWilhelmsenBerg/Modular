package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.Settings
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.CollisionChoice
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.DeletionPlan
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.EmptyTrashPlan
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FileEntry
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FileSort
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.DirectoryTotal
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.Cancellation
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.Progress
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FilesRepository
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.SearchFilter
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.TrashEntry
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.RestoreChoice
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.createFilesRepository
import io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner.CleanerEngine
import io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner.CleanerRepositoryAdapter
import io.github.alexanderwilhelmsenberg.modular.addon.files.archives.ArchivePageSupport
import io.github.alexanderwilhelmsenberg.modular.addon.files.archives.ArchiveExtractionReport
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.Base64
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Module-owned page state and command router. All calls are blocking and belong on an I/O thread. */
class FilesPageController(context: Context, private val repository: FilesRepository = createFilesRepository(context)) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("files-page", Context.MODE_PRIVATE)
    private val folderAppearance = FolderAppearance(preferences)
    private val lock = Any()
    private val commands = LinkedHashMap<String, Command>()
    private data class ScrollCommand(val revision: Long, val key: String)
    private val scrollCommands = LinkedHashMap<String, ScrollCommand>()
    private data class SizeProgressCommand(val revision: Long, val folderId: String)
    private val sizeProgressCommands = LinkedHashMap<String, SizeProgressCommand>()
    private var lastBrowserJson: String? = null
    private var lastBrowserRevision = -1L
    private var lastBrowserFolderId: String? = null
    private val completedTokens = LinkedHashSet<String>(preferences.getStringSet("completedTokens", emptySet()) ?: emptySet())
    private val pendingTokens = LinkedHashSet<String>(preferences.getStringSet("pendingTokens", emptySet()) ?: emptySet())
    private var revision = 0L
    private var currentOffset = preferences.getInt("currentOffset", 0)
    private var scrollAnchor = preferences.getInt("scrollAnchor", 0)
    private var scrollOffset = preferences.getInt("scrollOffset", 0)
    private var view = preferences.getString("view", "overview") ?: "overview"
    private var locationId = preferences.getString("location", null)
    private var folderId = preferences.getString("folder", null)
    private var query = preferences.getString("query", "") ?: ""
    private var sort = runCatching { FileSort.valueOf(preferences.getString("sort", "NAME") ?: "NAME") }.getOrDefault(FileSort.NAME)
    private var showHidden = preferences.getBoolean("hidden", false)
    private var grid = preferences.getBoolean("grid", false)
    private var includeSubfolders = preferences.getBoolean("subfolders", true)
    private var allLocations = preferences.getBoolean("allLocations", false)
    private var mimePrefix = preferences.getString("mimePrefix", null)
    private var modifiedAfter = preferences.getLong("modifiedAfter", -1).takeIf { it >= 0 }
    private var modifiedBefore = preferences.getLong("modifiedBefore", -1).takeIf { it >= 0 }
    private var minSizeBytes = preferences.getLong("minSizeBytes", -1).takeIf { it >= 0 }
    private var maxSizeBytes = preferences.getLong("maxSizeBytes", -1).takeIf { it >= 0 }
    private var selected = linkedSetOf<String>()
    private val favorites = LinkedHashSet<String>(preferences.getStringSet("favorites", emptySet()) ?: emptySet())
    private val recent = LinkedHashSet<String>((preferences.getString("recent", "") ?: "").split(',').filter { it.isNotBlank() })
    private var propertyEntryId: String? = null
    private var appearanceFolderId: String? = null
    private var appearanceReturnView = "browser"
    private var infoReturnView = "browser"
    private var cleanBrowserPosition: Triple<Int, Int, Int>? = null
    private var historyDetailId: String? = null
    private var clipboard: List<String> = emptyList()
    private var clipboardMove = false
    private var destinationFolderId: String? = null
    private var pasteDestinationId: String? = null
    private var pasteSources: List<FileEntry> = emptyList()
    private var pasteSourceFingerprints: Map<String, String> = emptyMap()
    private var pasteCollisions: Map<String, FileEntry> = emptyMap()
    private var pasteCollisionFingerprints: Map<String, String> = emptyMap()
    private var pasteReplacementPlans: Map<String, DeletionPlan> = emptyMap()
    private var pasteChoice = CollisionChoice.SKIP
    private var review: DeletionPlan? = null
    private var emptyTrashReview: EmptyTrashPlan? = null
    private var offlineTrashPending = 0
    private var restoreEntries: List<TrashEntry> = emptyList()
    private var restoreConflicts: Map<String, FileEntry> = emptyMap()
    private var restoreMissingParents: Set<String> = emptySet()
    private var restorePlans: Map<String, DeletionPlan> = emptyMap()
    private val restoreChoices = linkedMapOf<String, RestoreChoice>()
    private var restoreTargetTrashId: String? = null
    private var restoreDestinationFolderId: String? = null
    private var reviewReturnView = "browser"
    private var message: String? = null
    private var mapFolderId: String? = null
    private var mapDepth = 0
    private var documentId = preferences.getString("documentId", null)
    private var documentKind = preferences.getString("documentKind", null)
    private var documentFingerprint = preferences.getString("documentFingerprint", null)
    private var documentSaveStatus: String? = null
    private val stagedDocumentWrites = mutableMapOf<String, String>()
    private val pendingSafWrites = mutableSetOf<String>()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "files-worker").apply { isDaemon = true } }
    private val sizeWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "files-size-worker").apply { isDaemon = true } }
    private val pendingTotals = mutableSetOf<String>()
    private val sizeScanRequested = mutableSetOf<String>()
    private val totalCancelFlags = mutableMapOf<String, AtomicBoolean>()
    private val canceledTotals = mutableSetOf<String>()
    private val computedTotals = mutableMapOf<String, DirectoryTotal>()
    private val totalErrors = mutableMapOf<String, String>()
    private var activeJobCancel: AtomicBoolean? = null
    private var cleanScanCancel: AtomicBoolean? = null
    private var cleanScanKey: String? = null
    private var cleanScanFolderId: String? = null
    private var cleanScanFolderName: String? = null
    private var cleanScanCategories: Set<CleanerEngine.Category> = emptySet()
    private var cleanScanIncludesSubfolders = true
    private var cleanSetup = true
    private var cleanSettingsFolderId: String? = null
    private var cleanScanId = 0L
    private val cleanProgressEntries = linkedMapOf<String, CleanerEngine.Entry>()
    private val cleanProgressGroups = linkedMapOf<String, CleanerEngine.Group>()
    private val cleanProgressSuggestions = linkedMapOf<Pair<String, CleanerEngine.Category>, CleanerEngine.Suggestion>()
    private val cleanProgressWarnings = mutableListOf<String>()
    private var cleanProgressFolders = 0
    private var cleanProgressFiles = 0
    private var jobStatus = ""
    private var jobReturnView = "browser"
    private val cleaner by lazy { CleanerRepositoryAdapter(repository, app) }
    private val archives by lazy { ArchivePageSupport(app, repository) }
    private var archiveDestinationFolderId: String? = null
    private var archiveReport: ArchiveExtractionReport? = null
    private var cleanerAnalysis: CleanerEngine.Analysis? = null
    private var cleanerScopeKey: String? = null
    private var cleanPresentedKey: String? = null
    private var cleanPresentedFolderId: String? = null
    private var cleanerPlan: CleanerEngine.Plan? = null
    private val cleanerKeepers = linkedMapOf<String, String>()
    private val cleanerSelectedGroups = linkedSetOf<String>()
    private val cleanerSelectedSuggestions = linkedSetOf<String>()
    private var keeperGroupId: String? = null
    private var reviewAfterKeeperGroupId: String? = null
    private var cleanReviewIssues: List<CleanerEngine.ReviewIssue> = emptyList()
    private var cleanerLargeMiB = preferences.getInt("cleanLargeMiB", 512)
    private var cleanerOldDays = preferences.getInt("cleanOldDays", 90)
    private var cleanerIncludeSubfolders = preferences.getBoolean("cleanSubfolders", true)
    private val cleanerCategories = (preferences.getStringSet("cleanChecks", null)?.mapNotNull { name ->
        runCatching { CleanerEngine.Category.valueOf(name) }.getOrNull()
    }?.toMutableSet() ?: mutableSetOf(CleanerEngine.Category.NUMBERED_COPY))
    private val cleanerExcludedIds = (preferences.getStringSet("cleanExcludedIds", emptySet()) ?: emptySet()).toMutableSet()

    private fun cleanKey(folder: String): String = listOf(folder, cleanerIncludeSubfolders,
        cleanerLargeMiB, cleanerOldDays, cleanerExcludedIds.sorted().joinToString("|"),
        cleanerCategories.map { it.name }.sorted().joinToString("|")).joinToString("::")

    data class HostHandoff(val kind: String, val documentId: String?)
    private data class TimedHandoff(val value: HostHandoff, val expiresAt: Long)
    private val handoffs = linkedMapOf<String, TimedHandoff>()
    private var pendingHostLaunch: JSONObject? = null
    private data class Command(val revision: Long, val kind: String, val destructive: Boolean,
        val hostAction: String?, val documentId: String?, val handler: (JSONObject) -> Unit)

    fun consumeHostHandoff(nonce: String): HostHandoff? = synchronized(lock) {
        if (nonce.length !in 32..64) return@synchronized null
        val item = handoffs.remove(nonce) ?: return@synchronized null
        item.value.takeIf { item.expiresAt >= System.currentTimeMillis() }
    }

    fun getPage(requestJson: String): String = synchronized(lock) {
        val request = JSONObject(requestJson)
        require(request.optInt("version") == 1) { "Unsupported page version" }
        request.optJSONObject("restoreScroll")?.let { restored ->
            recordScrollPosition(restored.optString("key"), restored.optInt("anchor", 0),
                restored.optInt("offset", 0))
        }
        val offset = request.optString("cursor").toIntOrNull()?.coerceAtLeast(0) ?: currentOffset
        val pageSize = request.optInt("pageSize", 100).coerceIn(1, 100)
        currentOffset = offset
        render(offset, pageSize)
    }

    /** Saves position without replacing page actions or triggering a page render. */
    fun recordScrollPosition(key: String, anchor: Int, offset: Int): Unit = synchronized(lock) {
        if ((view == "browser" || view == "search") && key == scrollKey()) {
            val safeAnchor = anchor.coerceIn(0, 100)
            val safeOffset = offset.coerceIn(0, 100_000)
            if (scrollAnchor != safeAnchor || scrollOffset != safeOffset) {
                scrollAnchor = safeAnchor
                scrollOffset = safeOffset
                persist()
            }
        }
    }

    fun performAction(requestJson: String): String = synchronized(lock) {
        val request = JSONObject(requestJson)
        require(request.optInt("version") == 1) { "Unsupported page version" }
        val id = request.optString("actionId")
        val token = request.optString("operationToken")
        require(token.length in 16..128) { "Missing operation token" }
        request.optJSONObject("restoreScroll")?.let { restored ->
            recordScrollPosition(restored.optString("key"), restored.optInt("anchor", 0),
                restored.optInt("offset", 0))
        }
        scrollCommands[id]?.let { scroll ->
            if (scroll.revision == revision && (view == "browser" || view == "search") &&
                scroll.key == scrollKey()) {
                val args = request.optJSONObject("args") ?: JSONObject()
                recordScrollPosition(scroll.key, args.optInt("anchor", 0), args.optInt("offset", 0))
            }
            return@synchronized render(currentOffset, 100)
        }
        sizeProgressCommands[id]?.let { polling ->
            if (polling.revision == revision && view == "browser" && folderId == polling.folderId &&
                lastBrowserRevision == revision && lastBrowserFolderId == polling.folderId)
                return@synchronized patchBrowserSizes()
            return@synchronized render(currentOffset, 100)
        }
        val command = commands[id] ?: run {
            message = "Action expired. Review the latest Files page."
            return@synchronized render(currentOffset, 100)
        }
        if (command.revision != revision) {
            message = "Files changed. Review the latest page."
            return@synchronized render(currentOffset, 100)
        }
        if (cleanScanCancel != null && (command.destructive || command.kind in
                setOf("review", "confirm", "create", "createFile", "rename", "paste", "move"))) {
            message = "Finish or cancel the cleanup scan before changing files or preparing deletion"
            return@synchronized render(currentOffset, 100)
        }
        if (command.destructive && token in completedTokens) return@synchronized render(currentOffset, 100)
        if (command.destructive && token in pendingTokens) {
            message = "This operation was interrupted. Refresh and review storage before retrying."
            return@synchronized render(currentOffset, 100)
        }
        if (command.destructive) {
            pendingTokens.add(token)
            preferences.edit().putStringSet("pendingTokens", pendingTokens.toSet()).commit()
        }
        val oldView = view
        val oldFolderId = folderId
        val oldLocationId = locationId
        val outcome = runCatching { command.handler(request.optJSONObject("args") ?: JSONObject()) }
        val restoreBrowserPosition = if (view == "browser" && oldView in setOf("clean", "progress"))
            cleanBrowserPosition else null
        if (restoreBrowserPosition != null) {
            currentOffset = restoreBrowserPosition.first
            scrollAnchor = restoreBrowserPosition.second
            scrollOffset = restoreBrowserPosition.third
            cleanBrowserPosition = null
        } else if (view != oldView || folderId != oldFolderId || locationId != oldLocationId) {
            currentOffset = 0; scrollAnchor = 0; scrollOffset = 0
        }
        if (outcome.isSuccess && command.hostAction != null) {
            val nonce = UUID.randomUUID().toString().replace("-", "")
            handoffs.entries.removeIf { it.value.expiresAt < System.currentTimeMillis() }
            if (handoffs.size >= 16) handoffs.remove(handoffs.keys.first())
            handoffs[nonce] = TimedHandoff(HostHandoff(command.hostAction, command.documentId),
                System.currentTimeMillis() + 60_000)
            pendingHostLaunch = JSONObject().put("kind", command.hostAction).put("nonce", nonce)
        }
        if (outcome.isFailure) {
            message = outcome.exceptionOrNull()?.message ?: "File action failed"
            if (view == "markdown") documentSaveStatus = "failed"
        }
        else if (command.destructive) {
            pendingTokens.remove(token)
            completedTokens.add(token)
            while (completedTokens.size > 128) completedTokens.remove(completedTokens.first())
            preferences.edit().putStringSet("pendingTokens", pendingTokens.toSet())
                .putStringSet("completedTokens", completedTokens.toSet()).commit()
        }
        val progressRefresh = command.kind == "refresh" && oldView == "progress"
        if (command.kind == "documentCommit" || command.kind == "documentCancel") {
            if (outcome.isSuccess) commands.remove(id)
        } else if (!progressRefresh) {
            revision++
            commands.clear()
        }
        if (!progressRefresh) persist()
        render(currentOffset, 100)
    }

    fun openDocument(documentId: String, mode: String): ParcelFileDescriptor? = synchronized(lock) {
        require(mode == "r" || mode.matches(Regex("rw:[A-Za-z0-9._:-]{1,128}"))) { "Unsupported document mode" }
        require(documentId.length <= 512) { "Document ID too long" }
        if (documentId.startsWith("image:")) {
            require(mode == "r") { "Local images are read-only" }
            val parts = documentId.split(':', limit = 3)
            require(parts.size == 3 && parts[1] == this.documentId && view == "markdown") { "Image is outside the open document" }
            val path = String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8)
            require(path.length in 1..256 && !path.startsWith('/') && !path.startsWith('\\')) { "Invalid image path" }
            val segments = path.replace('\\', '/').split('/')
            require(segments.isNotEmpty() && segments.size <= 16 && segments.all {
                it.isNotBlank() && it != "." && it != ".." && it.length <= 255
            }) { "Invalid image path" }
            var parentId = repository.get(parts[1]).parentId ?: throw IllegalArgumentException("Document has no parent folder")
            for ((index, name) in segments.withIndex()) {
                var offset = 0
                var found: FileEntry? = null
                do {
                    val page = repository.list(parentId, offset, 100)
                    found = page.entries.firstOrNull { it.name == name }
                    if (found != null || page.nextOffset == null) break
                    require(page.nextOffset > offset) { "Incomplete image lookup" }
                    offset = page.nextOffset
                } while (true)
                val child = found ?: throw IllegalArgumentException("Image unavailable")
                if (index < segments.lastIndex) require(child.isDirectory) { "Image path is not a folder" }
                parentId = child.id
            }
            val image = repository.get(parentId)
            require(!image.isDirectory && image.readable && image.mimeType?.startsWith("image/") == true)
                { "Local resource is not a readable image" }
            require(image.sizeBytes == null || image.sizeBytes <= 8L * 1024 * 1024) { "Image exceeds preview limit" }
            return@synchronized repository.openRead(image.id)
        }
        val entry = repository.get(documentId)
        require(!entry.isDirectory) { "Folder cannot be opened as a document" }
        if (mode == "r") repository.openRead(documentId)
        else {
            require(documentId == this.documentId && this.view == "markdown") { "Document is not active for editing" }
            val expected = mode.removePrefix("rw:")
            require(expected == documentFingerprint) { "Document version changed; reopen it" }
            documentSaveStatus = null
            val location = repository.locations().firstOrNull { it.id == entry.locationId }
                ?: throw IllegalStateException("Storage location unavailable")
            if (location.kind == io.github.alexanderwilhelmsenberg.modular.addon.files.storage.StorageLocation.Kind.SELECTED_FOLDER) {
                repository.openWrite(documentId, expected).also { pendingSafWrites.add(documentId) }
            } else {
                require(documentId !in stagedDocumentWrites) { "A save is already pending; review or cancel it" }
                repository.beginStagedWrite(documentId, expected).also {
                    stagedDocumentWrites[documentId] = it.token
                }.descriptor
            }
        }
    }

    fun addedLocation(locationId: String) = synchronized(lock) {
        locationId.also { this.locationId = it }
        folderId = repository.locations().first { it.id == locationId }.rootId
        view = "browser"
        message = null
        persist()
    }

    fun openSettings() = synchronized(lock) { cleanSettingsFolderId = null; view = "settings"; persist() }

    private fun render(offset: Int, limit: Int): String {
        if (commands.size > 2048) commands.clear()
        val page = JSONObject().put("version", 1).put("kind", "files")
            .put("title", when (view) { "trash" -> "Trash"; "review" -> "Review deletion";
                "emptyTrashReview" -> "Empty trash"; "map" -> "Storage map"; "clean" -> "Clean folder";
                "settings" -> "Files settings"; "cleanReview" -> "Review cleanup";
            "info" -> "Folder info"; "folderAppearance" -> "Folder appearance";
            "history", "historyDetail" -> "Operation history"; "markdown" -> "Markdown"; "pdf" -> "PDF";
            "keeper" -> "Choose which to keep"; "filters" -> "Search filters";
            "archive" -> "Extract archive";
            "archiveReview" -> "Review extraction"; "archiveDestination" -> "Choose destination";
            "archiveReport" -> "Extraction result";
            "movePicker", "moveLocations" -> "Move destination"; "pasteReview" -> "Review paste";
            "properties" -> "Properties"; "restoreReview" -> "Review restore";
            "restoreDestination", "restoreLocations" -> "Restore destination";
            "archiveLocations" -> "Archive destination"; else -> "Files" })
            .put("view", view).put("locationId", locationId).put("folderId", folderId)
            .put("layout", if (grid) "details" else "list")
            .put("cleanScanRunning", cleanScanCancel != null)
            .put("scrollAnchor", scrollAnchor).put("scrollOffset", scrollOffset)
            .put("state", "ready")
        if (view == "clean" || (view == "progress" && jobReturnView == "clean")) {
            page.put("presentation", "sheet").put("underlyingView", "browser")
        }
        if (view == "browser" || view == "search") page.put("scrollKey", scrollKey())
        message?.let { page.put("message", it) }
        documentSaveStatus?.let { page.put("documentSaveStatus", it) }
        pendingHostLaunch?.let { page.put("hostLaunch", it); pendingHostLaunch = null }
        val actions = JSONArray()
        val items = JSONArray()
        val trash = runCatching { repository.trashSummary() }.getOrNull()
        if (trash != null) page.put("trash", JSONObject().put("fileCount", trash.fileCount)
            .put("emptyFolderCount", trash.emptyFolderCount).put("knownBytes", trash.knownBytes))
        if (view != "trash" && view != "clean" && view != "progress")
            action(actions, "Trash", "trash") { view = "trash"; selected.clear() }
        when (view) {
            "overview" -> renderOverview(page, actions, items, offset, limit)
            "browser", "search" -> renderBrowser(page, actions, items, offset, limit)
            "review" -> renderReview(page, actions, items, offset, limit)
            "trash" -> renderTrash(page, actions, items, offset, limit)
            "emptyTrashReview" -> renderEmptyTrashReview(page, actions, items, offset, limit)
            "map" -> renderMap(page, actions, items, offset, limit)
            "clean" -> renderClean(page, actions, items, offset, limit)
            "keeper" -> renderKeeper(page, actions, items, offset, limit)
            "cleanReview" -> renderCleanReview(page, actions, items, offset, limit)
            "settings" -> renderSettings(page, actions)
            "info" -> renderInfo(page, actions, items)
            "folderAppearance" -> renderFolderAppearance(page, actions)
            "history" -> renderHistory(page, actions, items, offset, limit)
            "historyDetail" -> renderHistoryDetail(page, actions, items, offset, limit)
            "markdown", "pdf" -> renderDocument(page, actions)
            "progress" -> renderProgress(page, actions, items, offset, limit)
            "archive" -> renderArchive(page, actions)
            "archiveReview" -> renderArchiveReview(page, actions, items, offset, limit)
            "archiveDestination" -> renderArchiveDestination(page, actions, items, offset, limit)
            "archiveReport" -> renderArchiveReport(page, actions, items, offset, limit)
            "filters" -> renderFilters(page, actions)
            "movePicker" -> renderMovePicker(page, actions, items, offset, limit)
            "pasteReview" -> renderPasteReview(page, actions, items, offset, limit)
            "properties" -> renderProperties(page, actions, items)
            "restoreReview" -> renderRestoreReview(page, actions, items, offset, limit)
            "restoreDestination" -> renderRestoreDestination(page, actions, items, offset, limit)
            "moveLocations", "restoreLocations", "archiveLocations" -> renderDestinationLocations(page, actions, items, offset, limit)
            else -> { view = "overview"; renderOverview(page, actions, items, offset, limit) }
        }
        page.put("actions", actions).put("items", items)
        val rendered = page.toString()
        if (rendered.toByteArray().size > 250_000) return errorPage("Page too large. Narrow the folder or search.")
        if (view == "browser") {
            lastBrowserJson = rendered
            lastBrowserRevision = revision
            lastBrowserFolderId = folderId
        }
        return rendered
    }

    private fun renderOverview(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        page.put("summary", "Connected locations. Choose a folder to browse.")
        action(actions, "Add folder", "setup", hostAction = "pickFolder") { message = "Choose a folder in Android’s picker" }
        action(actions, "Search", "search", input = "query", initial = query) { args ->
            val location = repository.locations().firstOrNull { it.available }
                ?: throw IllegalStateException("Connect a storage location before searching")
            locationId = location.id
            folderId = location.rootId
            query = args.optString("query").take(200)
            view = if (query.isBlank()) "browser" else "search"
        }
        action(actions, "Full storage access", "settings") { view = "settings" }
        action(actions, "Info", "info") { infoReturnView = view; view = "info" }
        val locations = repository.locations()
        locations.drop(offset).take(limit).forEach { location ->
            val item = JSONObject().put("id", location.id).put("name", location.title)
                .put("kind", "location").put("subtitle", when {
                    !location.available -> "Reconnect required"
                    location.capacityBytes != null -> "${formatBytes(location.freeBytes)} free of ${formatBytes(location.capacityBytes)}"
                    else -> "Capacity unavailable"
                })
                .put("folderAppearance", folderAppearance.json(location.rootId, location.title))
            val rowActions = JSONArray()
            action(rowActions, if (location.available) "Open" else "Reconnect", "open",
                hostAction = if (location.available) null else "pickFolder") {
                locationId = location.id; folderId = location.rootId
                view = "browser"
            }
            item.put("actions", rowActions)
            items.put(item)
        }
        next(page, actions, offset, limit, locations.size)
        if (offset == 0) {
            for ((label, ids) in listOf("Favorite" to favorites.toList().take(12), "Recent" to recent.toList().reversed().take(12))) {
                for (id in ids) {
                    if (items.length() >= 100) break
                    val entry = runCatching { repository.get(id) }.getOrNull() ?: continue
                    val item = entryJson(entry, true).put("subtitle", "$label · ${entry.relativePath}")
                    items.put(item)
                }
            }
        }
    }

    private fun renderBrowser(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val folder = folderId ?: run { view = "overview"; renderOverview(page, actions, items, offset, limit); return }
        val location = repository.locations().firstOrNull { it.id == locationId }
        if (location == null || !location.available) {
            page.put("state", "unavailable").put("summary", "Remembered location is unavailable")
            action(actions, "Reconnect", "setup", hostAction = "pickFolder") { message = "Reconnect this location in Android’s picker" }
            action(actions, "Locations", "back") { view = "overview" }
            return
        }
        val current = runCatching { repository.get(folder) }.getOrElse {
            page.put("state", "unavailable").put("summary", "Folder access was lost")
            action(actions, "Reconnect", "setup", hostAction = "pickFolder") { message = "Reconnect this location in Android’s picker" }
            return
        }
        page.put("title", current.name.ifBlank { location.title })
        page.put("currentFolder", folderAppearance.json(current.id, current.name.ifBlank { location.title }))
        val total = knownTotal(folder)
        page.put("summary", buildString {
            append(if (total == null && folder in pendingTotals) "Calculating folder size…" else formatBytes(total?.bytes))
            if (total?.complete == false) append(" · Partial")
        })
        val breadcrumbs = JSONArray()
        var crumb: FileEntry? = current
        var n = 0
        while (crumb != null && n++ < 16) {
            val target = crumb.id
            val a = registered("Open", "open") { folderId = target; view = "browser"; selected.clear() }
            breadcrumbs.put(0, JSONObject().put("id", target).put("name", crumb.name)
                .put("folderAppearance", folderAppearance.json(target, crumb.name)).put("action", a))
            crumb = crumb.parentId?.let { runCatching { repository.get(it) }.getOrNull() }
        }
        page.put("breadcrumbs", breadcrumbs)
        when {
            selected.isNotEmpty() -> action(actions, "Clear selection", "back") { selected.clear() }
            view == "search" -> action(actions, "Close search", "back") { view = "browser"; query = "" }
            current.parentId != null -> action(actions, "Up", "back") { folderId = current.parentId; view = "browser" }
            else -> action(actions, "Locations", "back") { view = "overview" }
        }
        action(actions, "Search", "search", input = "query", initial = query) { args ->
            query = args.optString("query").take(200); view = if (query.isBlank()) "browser" else "search"
        }
        action(actions, "Filters", "filters") { view = "filters" }
        if (view == "search") {
            action(actions, if (includeSubfolders) "Subfolders on" else "Subfolders off", "toggle") { includeSubfolders = !includeSubfolders }
            action(actions, "Delete all results", "review") {
                val roots = searchRoots(folder)
                val filter = searchFilter()
                startJob("Preparing all search results", "review") { cancel, _ ->
                    val ids = linkedSetOf<String>()
                    var searchOffset = 0
                    do {
                        val batch = repository.searchPage(roots, filter, searchOffset, 100, cancel)
                        require(batch.complete && batch.errors.isEmpty()) { "Search coverage incomplete; review cannot begin" }
                        ids.addAll(batch.entries.map { it.id })
                        val next = batch.nextOffset ?: break
                        require(next > searchOffset) { "Search pagination did not advance" }
                        searchOffset = next
                    } while (true)
                    val plan = repository.prepareDeletion(ids.toList(), cancel)
                    synchronized(lock) { review = plan; reviewReturnView = "search" }
                    "${plan.fileCount} affected files captured for review"
                }
            }
        }
        action(actions, "Sort ${sort.name.lowercase()}", "sort") { sort = FileSort.entries[(sort.ordinal + 1) % FileSort.entries.size] }
        action(actions, if (grid) "List view" else "Details view", "layout") { grid = !grid }
        action(actions, if (showHidden) "Show hidden" else "Hide hidden", "toggle") { showHidden = !showHidden }
        action(actions, "Clean", "clean") { openClean(folder) }
        action(actions, "Info", "info") { infoReturnView = view; view = "info" }
        action(actions, "Customize folder", "folderAppearance") {
            appearanceFolderId = folder; appearanceReturnView = view; view = "folderAppearance"
        }
        action(actions, "Storage map", "map") { mapFolderId = folder; mapDepth = 0; view = "map" }
        action(actions, "New folder", "create", input = "name") { args ->
            repository.createDirectory(folder, safeName(args.optString("name"))); message = "Folder created"
        }
        action(actions, "New file", "createFile", input = "textFile") { args ->
            val (name, mime) = textFileName(args.optString("name"), args.optString("extension", "txt"))
            repository.createFile(folder, name, mime)
            message = "Created $name"
        }
        action(actions, "Settings", "settings") { view = "settings" }
        action(actions, "History", "history") { view = "history" }
        action(actions, "Refresh", "refresh") {
            if (cleanScanCancel == null) {
                computedTotals.remove(folder); totalErrors.remove(folder)
                sizeScanRequested.remove(folder); canceledTotals.remove(folder)
                repository.invalidateListing(folder)
            }
        }
        action(actions, "Remember position", "scroll") { args ->
            scrollAnchor = args.optInt("anchor", 0).coerceIn(0, 100)
            scrollOffset = args.optInt("offset", 0).coerceIn(0, 100_000)
        }
        if (selected.isNotEmpty()) {
            action(actions, "Delete ${selected.size}", "review") {
                review = repository.prepareDeletion(selected.toList()); reviewReturnView = view; view = "review"
            }
            action(actions, "Copy ${selected.size}", "copy") { clipboard = selected.toList(); clipboardMove = false; selected.clear() }
            action(actions, "Cut ${selected.size}", "cut") { clipboard = selected.toList(); clipboardMove = true; selected.clear() }
            action(actions, "Move ${selected.size}…", "move") {
                clipboard = selected.toList(); clipboardMove = true; selected.clear()
                destinationFolderId = folder; view = "movePicker"
            }
            action(actions, "Clear selection", "cancel") { selected.clear() }
            action(actions, "Exclude from Clean", "exclude") {
                cleanerExcludedIds.addAll(selected); selected.clear(); message = "Items excluded from future cleanup scans"
            }
        }
        if (clipboard.isNotEmpty()) {
            action(actions, "Paste ${clipboard.size}", "paste") {
                preparePaste(folder)
            }
            action(actions, "Cancel ${if (clipboardMove) "cut" else "copy"}", "cancel") { clipboard = emptyList() }
        }
        val result = if (view == "search") repository.searchPage(searchRoots(folder), searchFilter(), offset, limit) else null
        val listing = if (result == null) repository.cachedListing(folder, offset, limit, sort, showHidden)
            ?: repository.list(folder, offset, limit, sort, showHidden) else null
        val entries = result?.entries ?: listing!!.entries
        if (result != null) page.put("summary", "Search in ${if (allLocations) "all connected locations" else location.title}: ${result.totalCount ?: "many"} matches")
        if (view == "browser" && grid && entries.any { it.isDirectory &&
                (computedTotals[it.id] ?: repository.cachedDirectoryTotal(it.id))?.complete != true }) {
            queueDirectoryTotal(folder)
        }
        val sizesCalculating = view == "browser" && grid && folder in pendingTotals
        page.put("sizesCalculating", sizesCalculating)
        if (sizesCalculating) action(actions, "Update folder sizes", "sizeProgress") { }
        val portion = entries
        portion.forEach { entry -> items.put(entryJson(entry, includeActions = true)) }
        next(page, actions, offset, limit, result?.totalCount ?: listing?.totalCount ?: (offset + entries.size + if (result?.nextOffset != null || listing?.nextOffset != null) 1 else 0))
        page.put("selectedIds", JSONArray(selected.toList()))
        page.put("clipboard", JSONObject().put("count", clipboard.size).put("mode", if (clipboardMove) "cut" else "copy"))
    }

    private fun entryJson(entry: FileEntry, includeActions: Boolean): JSONObject {
        val item = JSONObject().put("id", entry.id).put("name", entry.name)
            .put("kind", if (entry.isDirectory) "folder" else "file")
            .put("path", entry.relativePath).put("sizeBytes", if (entry.isDirectory)
                (computedTotals[entry.id] ?: repository.cachedDirectoryTotal(entry.id))
                    ?.takeIf { it.complete }?.bytes
                else entry.sizeBytes)
            .put("modifiedAt", entry.modifiedAtMillis).put("createdAt", entry.createdAtMillis)
            .put("mimeType", entry.mimeType)
        if (entry.isDirectory) item.put("folderAppearance", folderAppearance.json(entry.id, entry.name))
        if (includeActions) {
            val row = JSONArray()
            val extension = entry.name.substringAfterLast('.', "").lowercase()
            val internal = !entry.isDirectory && extension in setOf("md", "markdown", "mdown", "pdf")
            action(row, "Open", "open", hostAction = if (!entry.isDirectory && !internal) "openDocument" else null,
                documentId = if (!entry.isDirectory && !internal) entry.id else null) {
                recent.remove(entry.id); recent.add(entry.id)
                while (recent.size > 30) recent.remove(recent.first())
                if (entry.isDirectory) { locationId = entry.locationId; folderId = entry.id; view = "browser"; selected.clear() }
                else {
                    val newKind = when {
                        extension in setOf("md", "markdown", "mdown") -> "markdown"
                        extension == "pdf" -> "pdf"
                        else -> null
                    }
                    if (newKind != null) {
                        documentId = entry.id; documentKind = newKind
                        documentFingerprint = if (newKind == "markdown") repository.fingerprint(entry.id) else null
                        documentSaveStatus = null
                        view = newKind
                    } else message = "Opening ${entry.name}"
                }
            }
            action(row, if (entry.id in selected) "Selected" else "Select", "select") {
                if (!selected.add(entry.id)) selected.remove(entry.id)
            }
            if (!entry.isDirectory) {
                action(row, "Open with", "openExternal", hostAction = "openDocument", documentId = entry.id) { }
                val archiveExt = entry.name.lowercase()
                if (listOf(".zip", ".7z", ".rar", ".tar", ".tar.gz", ".tgz").any(archiveExt::endsWith)) {
                    action(row, "Extract", "archive") { archives.begin(entry.id); view = "archive" }
                }
            }
            if (entry.writable) action(row, "Rename", "rename", input = "name", initial = entry.name) { args ->
                val renamed = repository.rename(entry.id, safeName(args.optString("name")))
                if (favorites.remove(entry.id)) favorites.add(renamed.id)
                message = "Renamed to ${renamed.name}"
            }
            action(row, "Properties", "properties") { propertyEntryId = entry.id; view = "properties" }
            if (entry.isDirectory) action(row, "Customize folder", "folderAppearance") {
                appearanceFolderId = entry.id; appearanceReturnView = view; view = "folderAppearance"
            }
            action(row, if (entry.id in favorites) "Unfavorite" else "Favorite", "favorite") {
                if (!favorites.add(entry.id)) favorites.remove(entry.id)
            }
            if (!entry.isDirectory) action(row, "Share", "share", hostAction = "shareDocument", documentId = entry.id) { }
            item.put("actions", row)
        }
        return item
    }

    private fun renderReview(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val plan = review ?: run { view = reviewReturnView; return }
        page.put("summary", "${plan.fileCount} files · ${formatBytes(plan.totalKnownBytes)} · Move to trash")
            .put("complete", plan.complete && plan.errors.isEmpty())
        if (plan.errors.isNotEmpty()) page.put("message", plan.errors.take(3).joinToString("; "))
        plan.descendants.drop(offset).take(limit).forEach { items.put(entryJson(it, false)) }
        next(page, actions, offset, limit, plan.descendants.size)
        action(actions, "Cancel", "cancel") { review = null; view = reviewReturnView }
        action(actions, "Back", "back") { review = null; view = reviewReturnView }
        if (plan.complete && plan.errors.isEmpty()) action(actions, "Delete", "confirm", destructive = true) {
            val returnView = reviewReturnView
            startJob("Moving reviewed files to trash", returnView) { cancel, progress ->
                val result = repository.moveToTrash(plan, cancel, progress)
                synchronized(lock) { review = null; selected.clear() }
                "Moved ${result.outcomes.count { it.status.name == "SUCCESS" }} items to trash"
            }
        }
    }

    private fun renderTrash(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val all = repository.trashEntries()
        val summary = repository.trashSummary()
        page.put("summary", "${summary.fileCount} files · ${formatBytes(summary.knownBytes)} in trash")
        action(actions, "Back", "back") { view = if (folderId == null) "overview" else "browser"; selected.clear() }
        if (all.isNotEmpty()) {
            action(actions, "Restore all", "restore") { prepareRestoreReview(all.map { it.id }) }
            action(actions, "Empty trash", "review") {
                val online = all.filter { it.available }
                offlineTrashPending = all.size - online.size
                if (online.isEmpty()) message = "Trash is on unavailable storage; reconnect before emptying"
                else startJob("Preparing permanent trash review", "emptyTrashReview") { _, _ ->
                    val plan = repository.prepareEmptyTrash(online.map { it.id })
                    synchronized(lock) { emptyTrashReview = plan }
                    "${plan.entries.size} available trash entries ready for review; $offlineTrashPending offline remain pending"
                }
            }
        }
        if (selected.isNotEmpty()) action(actions, "Restore selected", "restore") { prepareRestoreReview(selected.toList()) }
        all.drop(offset).take(limit).forEach { item ->
            val json = JSONObject().put("id", item.id).put("name", item.originalName)
                .put("kind", "trash").put("path", item.originalPath)
                .put("sizeBytes", item.sizeBytes).put("modifiedAt", item.modifiedAtMillis)
                .put("createdAt", item.createdAtMillis)
                .put("subtitle", if (item.available) "Trashed ${java.util.Date(item.trashedAtMillis)}" else "Drive unavailable")
            val row = JSONArray()
            action(row, "Restore", "restore") { prepareRestoreReview(listOf(item.id)) }
            action(row, "Select", "select") { if (!selected.add(item.id)) selected.remove(item.id) }
            items.put(json.put("actions", row))
        }
        next(page, actions, offset, limit, all.size)
    }

    private fun prepareRestoreReview(ids: List<String>) {
        startJob("Preparing restore review", "restoreReview") { cancel, _ ->
            val records = repository.trashEntries().filter { it.id in ids.toSet() }
            val conflicts = linkedMapOf<String, FileEntry>()
            val missing = linkedSetOf<String>()
            val plans = linkedMapOf<String, DeletionPlan>()
            for (record in records) {
                if (cancel.isCanceled()) throw IllegalStateException("Restore review canceled")
                val parent = runCatching { repository.get(record.originalParentId) }.getOrNull()
                if (parent == null || !parent.isDirectory || !parent.writable) { missing.add(record.id); continue }
                var offset = 0
                while (true) {
                    val page = repository.list(parent.id, offset, 100)
                    val existing = page.entries.firstOrNull { it.name == record.originalName }
                    if (existing != null) {
                        conflicts[record.id] = existing
                        plans[record.id] = repository.prepareDeletion(listOf(existing.id))
                        break
                    }
                    val next = page.nextOffset ?: break
                    require(next > offset && page.complete) { "Restore destination enumeration incomplete" }
                    offset = next
                }
            }
            synchronized(lock) {
                restoreEntries = records
                restoreConflicts = conflicts
                restoreMissingParents = missing
                restorePlans = plans
                restoreChoices.clear()
            }
            "${records.size} trash entries · ${conflicts.size} name collisions · ${missing.size} destinations need reconnect"
        }
    }

    private fun renderRestoreReview(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val records = restoreEntries
        val replacements = restoreChoices.filterValues { it.collision == CollisionChoice.REPLACE }.keys
            .flatMap { restorePlans[it]?.let { plan -> plan.roots + plan.descendants } ?: emptyList() }.distinctBy { it.id }
        val ready = records.isNotEmpty() && records.all { record ->
            val choice = restoreChoices[record.id]
            when {
                record.id in restoreMissingParents -> choice?.alternateParentId != null
                record.id in restoreConflicts -> choice != null &&
                    (choice.collision != CollisionChoice.REPLACE || restorePlans[record.id]?.let { it.complete && it.errors.isEmpty() } == true)
                else -> true
            }
        }
        page.put("summary", "${records.size} trash entries · ${restoreConflicts.size} name collisions · Restore keeps failures in trash")
            .put("complete", ready)
        action(actions, "Cancel", "cancel") { view = "trash"; restoreEntries = emptyList() }
        action(actions, "Back", "back") { view = "trash"; restoreEntries = emptyList() }
        if (ready) action(actions, "Restore", "confirm", destructive = true) {
            val ids = records.map { it.id }
            val choices = restoreChoices.toMap()
            startJob("Restoring reviewed trash", "trash") { cancel, _ ->
                val result = repository.restore(ids, choices, cancel)
                synchronized(lock) { restoreEntries = emptyList(); selected.clear() }
                "Restored ${result.outcomes.count { it.status.name == "SUCCESS" }} of ${ids.size}; ${result.outcomes.count { it.status.name != "SUCCESS" }} remain in trash"
            }
        }
        records.drop(offset).take(limit).forEach { record ->
            val conflict = restoreConflicts[record.id]
            val missing = record.id in restoreMissingParents
            val choice = restoreChoices[record.id]
            val row = JSONArray()
            if (conflict != null) {
                action(row, "Keep both", "restoreChoice") {
                    restoreChoices[record.id] = RestoreChoice(CollisionChoice.KEEP_BOTH)
                }
                action(row, "Replace reviewed", "restoreChoice") {
                    val plan = restorePlans[record.id] ?: throw IllegalStateException("Replacement needs review")
                    require(plan.complete && plan.errors.isEmpty()) { "Replacement review incomplete" }
                    restoreChoices[record.id] = RestoreChoice(CollisionChoice.REPLACE,
                        replacementPlans = mapOf(conflict.id to plan))
                }
            }
            if (missing || conflict != null) action(row, "Another folder", "destination") {
                restoreTargetTrashId = record.id
                restoreDestinationFolderId = folderId ?: repository.locations().firstOrNull { it.available }?.rootId
                view = "restoreDestination"
            }
            items.put(JSONObject().put("id", record.id).put("name", record.originalName)
                .put("kind", "trash").put("path", record.originalPath)
                .put("subtitle", when {
                    missing -> "Original destination unavailable; choose another folder"
                    conflict != null -> "Collision: ${conflict.relativePath} · ${choice?.collision?.name ?: "choose action"}"
                    else -> "Restore to original location"
                })
                .put("sizeBytes", record.sizeBytes).put("modifiedAt", record.modifiedAtMillis)
                .put("createdAt", record.createdAtMillis).put("actions", row))
        }
        val replacementOffset = (offset - records.size).coerceAtLeast(0)
        replacements.drop(replacementOffset).take((limit - (records.size - offset).coerceIn(0, limit)).coerceAtLeast(0)).forEach { item ->
            items.put(entryJson(item, false).put("subtitle", "Existing destination moved to trash if replaced"))
        }
        next(page, actions, offset, limit, records.size + replacements.size)
    }

    private fun renderRestoreDestination(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val targetId = restoreTargetTrashId ?: run { view = "restoreReview"; return }
        val current = restoreDestinationFolderId ?: run { view = "restoreReview"; return }
        val folder = repository.get(current)
        page.put("summary", "Choose a writable destination for ${restoreEntries.firstOrNull { it.id == targetId }?.originalName ?: "item"}")
            .put("currentFolderPath", folder.relativePath).put("destinationWritable", folder.writable)
        action(actions, "Back", "back") { view = "restoreReview" }
        action(actions, "Locations", "locations") { view = "restoreLocations" }
        if (folder.parentId != null) action(actions, "Up", "up") { restoreDestinationFolderId = folder.parentId }
        if (folder.writable) action(actions, "Choose destination", "chooseDestination") {
            restoreChoices[targetId] = RestoreChoice(CollisionChoice.KEEP_BOTH, alternateParentId = folder.id)
            view = "restoreReview"
        }
        val listing = repository.list(folder.id, offset, limit)
        listing.entries.filter { it.isDirectory }.forEach { child ->
            val row = JSONArray()
            action(row, "Open", "nav") { restoreDestinationFolderId = child.id }
            items.put(JSONObject().put("id", child.id).put("name", child.name)
                .put("kind", "folder").put("path", child.relativePath)
                .put("folderAppearance", folderAppearance.json(child.id, child.name)).put("actions", row))
        }
        next(page, actions, offset, limit, listing.totalCount ?: offset + listing.entries.size)
    }

    private fun renderEmptyTrashReview(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val plan = emptyTrashReview ?: run { view = "trash"; return }
        page.put("summary", "Permanently remove ${plan.entries.size} available trash entries · $offlineTrashPending offline remain pending")
            .put("complete", plan.complete && plan.errors.isEmpty())
        plan.descendants.drop(offset).take(limit).forEach { items.put(entryJson(it, false)) }
        next(page, actions, offset, limit, plan.descendants.size)
        action(actions, "Cancel", "cancel") { emptyTrashReview = null; view = "trash" }
        action(actions, "Back", "back") { emptyTrashReview = null; view = "trash" }
        if (plan.complete && plan.errors.isEmpty()) action(actions, "Empty permanently", "confirm", destructive = true) {
            startJob("Emptying reviewed trash", "trash") { cancel, _ ->
                val result = repository.emptyTrash(plan, cancel)
                synchronized(lock) { emptyTrashReview = null }
                "Permanently removed ${result.outcomes.count { it.status.name == "SUCCESS" }} entries; ${result.outcomes.count { it.status.name != "SUCCESS" }} retained for retry"
            }
        }
    }

    private fun renderMap(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val target = mapFolderId ?: folderId ?: run { view = "overview"; return }
        val folder = repository.get(target)
        val total = knownTotal(target)
        page.put("summary", "${folder.relativePath} · Scanned file coverage; volume capacity is separate")
            .put("mapPath", folder.relativePath).put("mapTotalBytes", total?.bytes)
            .put("mapComplete", total?.complete ?: false)
        action(actions, "Back to files", "back") { view = "browser" }
        if (folder.parentId != null) action(actions, "Up", "back") { mapFolderId = folder.parentId; mapDepth-- }
        action(actions, "Refresh map", "refresh") {
            canceledTotals.remove(target)
            repository.list(target, 0, 100).entries.forEach { canceledTotals.remove(it.id) }
        }
        val all = repository.list(target, offset, limit, FileSort.SIZE).entries
        val mapNodes = JSONArray()
        all.forEach { entry ->
            val entryTotal = if (entry.isDirectory) knownTotal(entry.id) else null
            val json = entryJson(entry, false).put("subtitle", "${entry.relativePath} · ${formatBytes(entryTotal?.bytes ?: entry.sizeBytes)}")
                .put("sizeBytes", entryTotal?.bytes ?: entry.sizeBytes)
                .put("complete", entryTotal?.complete ?: !entry.isDirectory)
            val row = JSONArray()
            if (entry.isDirectory) action(row, "Zoom", "open") { mapFolderId = entry.id; mapDepth++ }
            action(row, "Open", "open") { folderId = if (entry.isDirectory) entry.id else target; view = "browser" }
            json.put("actions", row)
            items.put(json)
            mapNodes.put(json)
        }
        page.put("mapNodes", mapNodes)
        page.put("mapCalculating", (target in pendingTotals && target !in canceledTotals) ||
            all.any { it.isDirectory && it.id in pendingTotals && it.id !in canceledTotals })
        if (page.optBoolean("mapCalculating")) action(actions, "Cancel analysis", "cancelMap") {
            (listOf(target) + all.filter { it.isDirectory }.map { it.id }).forEach { id ->
                canceledTotals.add(id); totalCancelFlags[id]?.set(true)
            }
            message = "Storage map analysis canceled; completed cached sizes remain available"
        }
        next(page, actions, offset, limit, repository.list(target, offset, limit).totalCount ?: offset + all.size)
    }

    private fun knownTotal(folder: String): DirectoryTotal? {
        val total = computedTotals[folder] ?: repository.cachedDirectoryTotal(folder)
        if (total == null) queueDirectoryTotal(folder)
        return total
    }

    private fun queueDirectoryTotal(folder: String) {
        if (folder !in canceledTotals && folder !in sizeScanRequested && pendingTotals.add(folder)) {
            sizeScanRequested.add(folder)
            val cancel = AtomicBoolean(false)
            totalCancelFlags[folder] = cancel
            sizeWorker.execute {
                val outcome = runCatching { repository.directoryTotal(folder, Cancellation { cancel.get() }) }
                synchronized(lock) {
                    if (!cancel.get()) outcome.onSuccess { computedTotals[folder] = it }
                        .onFailure { totalErrors[folder] = it.message ?: "Size unavailable" }
                    pendingTotals.remove(folder)
                    totalCancelFlags.remove(folder)
                }
            }
        }
    }

    /** Updates only size metadata, retaining every command ID in the current browser page. */
    private fun patchBrowserSizes(): String {
        val folder = folderId ?: return render(currentOffset, 100)
        val cached = lastBrowserJson ?: return render(currentOffset, 100)
        val page = JSONObject(cached)
        val total = computedTotals[folder] ?: repository.cachedDirectoryTotal(folder)
        page.put("summary", if (total == null && folder in pendingTotals) "Calculating folder size…"
            else formatBytes(total?.bytes) + if (total?.complete == false) " · Partial" else "")
        val items = page.optJSONArray("items") ?: JSONArray()
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            if (item.optString("kind") == "folder") {
                val childTotal = computedTotals[item.optString("id")]
                    ?: repository.cachedDirectoryTotal(item.optString("id"))
                item.put("sizeBytes", childTotal?.takeIf { it.complete }?.bytes)
            }
        }
        page.put("sizesCalculating", grid && folder in pendingTotals)
        return page.toString().also { lastBrowserJson = it }
    }

    private fun renderClean(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val folder = cleanPresentedFolderId ?: folderId ?: run { view = "overview"; return }
        val analysis = cleanerAnalysis?.takeIf { cleanerScopeKey == cleanPresentedKey }
        val include = analysis?.options()?.includeSubfolders() ?: cleanerIncludeSubfolders
        page.put("cleanScopeId", folder).put("cleanScopeName", analysis?.entries()?.get(folder)?.name()
            ?: runCatching { repository.get(folder).name }.getOrDefault("Selected folder"))
        action(actions, "Back to folder", "back") { view = "browser" }
        action(actions, "Clean settings", "settings") { cleanSettingsFolderId = folder; view = "settings" }
        if (cleanSetup || analysis == null) {
            page.put("cleanSetup", true).put("includeSubfolders", cleanerIncludeSubfolders)
                .put("cleanCanScan", cleanerCategories.isNotEmpty())
                .put("summary", "Choose the checks to run in this folder.")
            val checks = JSONArray()
            val categories = listOf(CleanerEngine.Category.NUMBERED_COPY, CleanerEngine.Category.OLDER_VERSION,
                CleanerEngine.Category.EXACT_DUPLICATE, CleanerEngine.Category.LARGE_FILE,
                CleanerEngine.Category.DOWNLOADED_INSTALLER_OR_ARCHIVE, CleanerEngine.Category.OLD_DOWNLOAD)
            for (category in categories) {
                val toggle = registered(cleanCategoryLabel(category), "toggleCleanCheck") {
                    if (!cleanerCategories.add(category)) cleanerCategories.remove(category)
                    cleanPresentedKey = cleanKey(folder)
                    message = null
                }
                checks.put(JSONObject().put("id", category.name).put("label", cleanCategoryLabel(category))
                    .put("description", cleanCategoryDescription(category))
                    .put("enabled", category in cleanerCategories)
                    .put("slow", category == CleanerEngine.Category.EXACT_DUPLICATE || category == CleanerEngine.Category.OLDER_VERSION)
                    .put("action", toggle))
            }
            page.put("cleanChecks", checks)
            action(actions, "Include subfolders", "toggleSubfolders") {
                cleanerIncludeSubfolders = !cleanerIncludeSubfolders
                cleanPresentedKey = cleanKey(folder)
            }
            action(actions, "Start scan", "scan") { startCleanScan(folder, force = true) }
            return
        }
        page.put("cleanSetup", false).put("includeSubfolders", include)
            .put("cleanChecksSummary", analysis.options().selectedCategories().sortedBy { it.ordinal }.joinToString { cleanCategoryLabel(it) })
            .put("summary", "${analysis.groups().size} copy/version groups · ${analysis.suggestions().size} optional findings · Highest priority and known space first")
        action(actions, "Scan again", "scan") { startCleanScan(folder, force = true) }
        action(actions, "Scan options", "configureClean") {
            clearCleanReviewIssues(); cleanSetup = true; cleanPresentedKey = cleanKey(folder)
        }
        if (message == null && analysis.coverageWarnings().isNotEmpty())
            page.put("message", analysis.coverageWarnings().take(2).joinToString("; "))
        val selectionCount = cleanerSelectedGroups.size + cleanerSelectedSuggestions.size
        page.put("cleanSelectionCount", selectionCount).put("cleanCanReview", selectionCount > 0)
        if (cleanReviewIssues.isNotEmpty()) {
            page.put("cleanIssueCount", cleanReviewIssues.size)
                .put("cleanIssues", JSONArray(cleanReviewIssues.take(20).map {
                    JSONObject().put("message", it.message())
                }))
        }
        val reviewAction = registered("Review selected", "review") {
            prepareCleanReview(analysis)
        }
        actions.put(reviewAction.put("enabled", selectionCount > 0))
        val clearAction = registered("Clear selection", "clearCleanSelection") {
            cleanerSelectedGroups.clear(); cleanerSelectedSuggestions.clear()
            clearCleanReviewIssues()
        }
        actions.put(clearAction.put("enabled", selectionCount > 0))
        val displayGroups = analysis.groups().map { group ->
            val chosen = cleanerKeepers[group.id()] ?: return@map group
            CleanerEngine.Group(group.id(), group.category(), group.memberIds(), chosen,
                group.requiresKeeperChoice(), group.baseName(), group.evidence())
        }
        val findings = CleanerEngine.rankedFindings(analysis.entries(), displayGroups, analysis.suggestions())
        page.put("findingCount", findings.size)
        findings.drop(offset).take(limit).forEach { finding ->
            val item = cleanFindingItem(finding, analysis.entries(), preliminary = false)
            val row = JSONArray()
            val group = finding.group()
            if (group != null) {
                action(row, if (group.id() in cleanerSelectedGroups) "Included" else "Include", "select") {
                    if (!cleanerSelectedGroups.add(group.id())) cleanerSelectedGroups.remove(group.id())
                    clearCleanReviewIssues()
                }
                action(row, "Choose keeper", "keeper") {
                    clearCleanReviewIssues(); keeperGroupId = group.id(); view = "keeper"
                }
            } else {
                val entryId = requireNotNull(finding.suggestion()).entryId()
                action(row, if (entryId in cleanerSelectedSuggestions) "Included" else "Include", "select") {
                    if (!cleanerSelectedSuggestions.add(entryId)) cleanerSelectedSuggestions.remove(entryId)
                    clearCleanReviewIssues()
                }
            }
            action(row, "Review", "reviewFinding") { reviewOnlyFinding(analysis, finding) }
            items.put(item.put("actions", row))
        }
        next(page, actions, offset, limit, findings.size)
    }

    private fun cleanFindingItem(finding: CleanerEngine.Finding, entries: Map<String, CleanerEngine.Entry>,
        preliminary: Boolean): JSONObject {
        val group = finding.group()
        val suggestion = finding.suggestion()
        val entry = suggestion?.let { entries[it.entryId()] }
        val details = if (group != null) "${cleanCategoryLabel(group.category())} · ${group.evidence()} · ${group.memberIds().size} items"
            else suggestion?.reason().orEmpty()
        return JSONObject().put("id", finding.id()).put("name", group?.baseName() ?: entry?.name() ?: cleanCategoryLabel(finding.category()))
            .put("kind", if (group != null) "group" else "suggestion")
            .put("subtitle", (if (preliminary) "Preliminary · " else "") + details)
            .put("priorityTier", finding.priorityTier()).put("priorityLabel", finding.priorityLabel())
            .put("reclaimableKnownBytes", if (group?.memberIds()?.any {
                val member = entries[it]
                member?.kind() == CleanerEngine.Kind.DIRECTORY && (preliminary || !member.childrenComplete())
            } == true) JSONObject.NULL else finding.knownReclaimableBytes())
            .put("unknownSizeFiles", finding.unknownSizeFiles()).put("sizeBytes", entry?.size())
    }

    private fun cleanCategoryLabel(category: CleanerEngine.Category): String = when (category) {
        CleanerEngine.Category.NUMBERED_COPY -> "Numbered copies"
        CleanerEngine.Category.EXACT_DUPLICATE -> "Exact duplicates"
        CleanerEngine.Category.OLDER_VERSION -> "Older builds and versions"
        CleanerEngine.Category.LARGE_FILE -> "Large files"
        CleanerEngine.Category.OLD_DOWNLOAD -> "Old downloads"
        CleanerEngine.Category.DOWNLOADED_INSTALLER_OR_ARCHIVE -> "Downloaded installers and archives"
    }

    private fun cleanCategoryDescription(category: CleanerEngine.Category): String = when (category) {
        CleanerEngine.Category.NUMBERED_COPY -> "Find names such as file, file (1), and file (2)."
        CleanerEngine.Category.EXACT_DUPLICATE -> "Compare contents of matching-size files."
        CleanerEngine.Category.OLDER_VERSION -> "Compare revision names and verified APK builds."
        CleanerEngine.Category.LARGE_FILE -> "Files of at least $cleanerLargeMiB MiB, for manual review."
        CleanerEngine.Category.OLD_DOWNLOAD -> "Older than $cleanerOldDays days. Age alone does not mean unwanted."
        CleanerEngine.Category.DOWNLOADED_INSTALLER_OR_ARCHIVE -> "Downloaded installers and archives, for manual review."
    }

    private fun openClean(folder: String) {
        if (view == "browser" || view == "search") {
            cleanBrowserPosition = Triple(currentOffset, scrollAnchor, scrollOffset)
        }
        if (cleanScanCancel != null) {
            cleanPresentedKey = cleanScanKey
            cleanPresentedFolderId = cleanScanFolderId
            cleanSetup = false
            view = "progress"
            return
        }
        if (activeJobCancel != null && jobReturnView == "cleanReview") {
            view = "progress"
            return
        }
        cleanPresentedFolderId = folder
        cleanPresentedKey = cleanKey(folder)
        cleanSetup = cleanerAnalysis == null || cleanerScopeKey != cleanPresentedKey
        if (cleanSetup) clearCleanReviewIssues()
        view = "clean"
    }

    private fun clearCleanReviewIssues() {
        cleanReviewIssues = emptyList()
        reviewAfterKeeperGroupId = null
        cleanerPlan = null
        message = null
    }

    private fun reviewOnlyFinding(analysis: CleanerEngine.Analysis, finding: CleanerEngine.Finding) {
        require(activeJobCancel == null && cleanScanCancel == null) { "Another file operation is running" }
        clearCleanReviewIssues()
        cleanerSelectedGroups.clear(); cleanerSelectedSuggestions.clear()
        val group = finding.group()
        if (group != null) {
            cleanerSelectedGroups.add(group.id())
            val chosen = cleanerKeepers[group.id()] ?: group.suggestedKeeperId()
            if (chosen == null || chosen !in group.memberIds()) {
                keeperGroupId = group.id()
                reviewAfterKeeperGroupId = group.id()
                message = "Choose a copy to keep before reviewing this group."
                view = "keeper"
                return
            }
        } else cleanerSelectedSuggestions.add(requireNotNull(finding.suggestion()).entryId())
        prepareCleanReview(analysis)
    }

    private fun recordBlockedReview(plan: CleanerEngine.Plan) {
        cleanerPlan = null
        cleanReviewIssues = plan.issues().filter {
            it.type() != CleanerEngine.ReviewIssueType.FINGERPRINT_PENDING
        }
        message = when (cleanReviewIssues.size) {
            0 -> "Review verification did not complete. Scan again before reviewing these items."
            1 -> cleanReviewIssues.first().message()
            else -> "${cleanReviewIssues.size} issues prevent this review. ${cleanReviewIssues.first().message()}"
        }
    }

    private fun prepareCleanReview(analysis: CleanerEngine.Analysis) {
        require(activeJobCancel == null && cleanScanCancel == null) { "Another file operation is running" }
        val keepers = cleanerKeepers.toMap()
        val groups = cleanerSelectedGroups.toSet()
        val suggestions = cleanerSelectedSuggestions.toSet()
        require(groups.isNotEmpty() || suggestions.isNotEmpty()) { "Select findings to review" }
        clearCleanReviewIssues()
        val cancel = AtomicBoolean(false)
        activeJobCancel = cancel
        cleanerPlan = null
        jobReturnView = "cleanReview"
        jobStatus = "Preparing review of selected files"
        cleanSetup = false
        view = "progress"
        worker.execute {
            val outcome = runCatching {
                cleaner.prepareReview(analysis, keepers, groups, suggestions, Cancellation { cancel.get() })
            }
            synchronized(lock) {
                if (activeJobCancel !== cancel) return@synchronized
                activeJobCancel = null
                if (!cancel.get() && outcome.isSuccess) {
                    val plan = outcome.getOrThrow()
                    if (plan.readyForReview()) cleanerPlan = plan else recordBlockedReview(plan)
                } else message = outcome.exceptionOrNull()?.message ?: "Review preparation canceled"
                if (view == "progress" && jobReturnView == "cleanReview")
                    view = if (cleanerPlan != null) "cleanReview" else "clean"
                persist()
            }
        }
    }

    private fun startCleanScan(folder: String, force: Boolean = false) {
        val excluded = cleanerExcludedIds.toSet()
        val include = cleanerIncludeSubfolders
        val largeMiB = cleanerLargeMiB
        val oldDays = cleanerOldDays
        val categories = cleanerCategories.toSet()
        val scopeKey = cleanKey(folder)
        if (view == "browser" || view == "search") {
            cleanBrowserPosition = Triple(currentOffset, scrollAnchor, scrollOffset)
        }
        if (cleanScanCancel != null) {
            cleanPresentedKey = cleanScanKey
            cleanPresentedFolderId = cleanScanFolderId
            view = "progress"
            return
        }
        if (!force && cleanerAnalysis != null && cleanerScopeKey == scopeKey) {
            cleanPresentedKey = scopeKey
            cleanPresentedFolderId = folder
            view = "clean"
            return
        }
        require(activeJobCancel == null) { "Another file operation is running" }
        require(categories.isNotEmpty()) { "Choose at least one check before scanning" }
        val options = CleanerEngine.Options(folder, include, excluded, emptySet(),
            largeMiB.toLong() * 1024 * 1024, oldDays.toLong(), Instant.now(), categories)
        canceledTotals.add(folder)
        totalCancelFlags[folder]?.set(true)
        val cancel = AtomicBoolean(false)
        val scanId = ++cleanScanId
        cleanScanCancel = cancel
        cleanScanKey = scopeKey
        cleanScanFolderId = folder
        cleanScanFolderName = runCatching { repository.get(folder).name }.getOrDefault("Selected folder")
        cleanScanCategories = categories
        cleanScanIncludesSubfolders = include
        cleanSetup = false
        clearCleanReviewIssues()
        cleanPresentedKey = scopeKey
        cleanPresentedFolderId = folder
        cleanProgressEntries.clear()
        cleanProgressGroups.clear()
        cleanProgressSuggestions.clear()
        cleanProgressWarnings.clear()
        cleanProgressFolders = 0
        cleanProgressFiles = 0
        jobReturnView = "clean"
        jobStatus = "Scanning cleanup candidates"
        view = "progress"
        worker.execute {
            val outcome = runCatching {
                val snapshot = cleaner.snapshot(folder, Cancellation { cancel.get() }, excluded,
                    progressiveOptions = options, onProgress = { batch ->
                        synchronized(lock) {
                            if (cleanScanId == scanId && !cancel.get()) {
                                cleanProgressEntries.putAll(batch.entries())
                                batch.groups().forEach { cleanProgressGroups[it.id()] = it }
                                batch.suggestions().forEach { cleanProgressSuggestions[it.entryId() to it.category()] = it }
                                cleanProgressWarnings.addAll(batch.coverageWarnings())
                                cleanProgressFolders = batch.foldersScanned()
                                cleanProgressFiles = batch.filesScanned()
                                jobStatus = "Scanning cleanup candidates · ${batch.foldersScanned()} folders · ${batch.filesScanned()} files"
                            }
                        }
                    })
                if (cancel.get()) throw IllegalStateException("Scan canceled")
                cleaner.analyze(snapshot, options, Cancellation { cancel.get() })
            }
            synchronized(lock) {
                if (cleanScanId != scanId) return@synchronized
                cleanScanCancel = null
                cleanProgressEntries.clear()
                cleanProgressGroups.clear()
                cleanProgressSuggestions.clear()
                cleanProgressWarnings.clear()
                if (!cancel.get() && outcome.isSuccess) {
                    val analysisResult = outcome.getOrThrow()
                    cleanerAnalysis = analysisResult
                    cleanerScopeKey = scopeKey
                    cleanerSelectedGroups.clear(); cleanerSelectedSuggestions.clear(); cleanerKeepers.clear()
                    preferences.edit().putLong("lastCleanScanAt:$folder", System.currentTimeMillis()).apply()
                    if (view == "progress" && jobReturnView == "clean" && cleanPresentedKey == scopeKey)
                        view = "clean"
                } else {
                    message = outcome.exceptionOrNull()?.message ?: "Scan canceled"
                    if (view == "progress" && jobReturnView == "clean") view = "browser"
                }
                persist()
            }
        }
    }

    private fun renderKeeper(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val analysis = cleanerAnalysis ?: run { view = "clean"; return }
        val group = analysis.groups().firstOrNull { it.id() == keeperGroupId } ?: run { view = "clean"; return }
        page.put("summary", "${group.memberIds().size} copies · ${group.evidence()}")
        action(actions, "Back to Clean", "back") { reviewAfterKeeperGroupId = null; message = null; view = "clean" }
        group.memberIds().drop(offset).take(limit).forEach { id ->
            val entry = analysis.entries()[id] ?: return@forEach
            val chosen = cleanerKeepers[group.id()] ?: group.suggestedKeeperId()
            val row = JSONArray()
            action(row, if (id == chosen) "Keeping" else "Keep this", "keeper") {
                val reviewThisGroup = reviewAfterKeeperGroupId == group.id()
                clearCleanReviewIssues()
                cleanerKeepers[group.id()] = id; cleanerSelectedGroups.add(group.id())
                if (reviewThisGroup) prepareCleanReview(analysis) else view = "clean"
            }
            items.put(JSONObject().put("id", id).put("name", entry.name())
                .put("kind", "candidate").put("path", entry.path())
                .put("sizeBytes", entry.size() ?: JSONObject.NULL).put("modifiedAt", entry.modified()?.toEpochMilli())
                .put("createdAt", entry.created()?.toEpochMilli()).put("actions", row))
        }
        next(page, actions, offset, limit, group.memberIds().size)
    }

    private fun renderCleanReview(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val plan = cleanerPlan ?: run { view = "clean"; return }
        val removalIds = plan.trashSteps().flatMap { listOf(it.entryId()) + it.reviewedDescendantIds() }.toSet()
        val manifest = plan.manifest().values.toList()
        page.put("summary", "${removalIds.size} affected entries · ${formatBytes(plan.knownSuggestedBytes())} known · ${plan.unknownSizeFiles()} unknown-size files · ${plan.keeperIds().size} kept · ${plan.renameSteps().size} pending renames · Move to trash")
            .put("complete", plan.readyForReview())
        if (plan.blockers().isNotEmpty()) page.put("message", plan.blockers().take(3).joinToString("; "))
        manifest.drop(offset).take(limit).forEach { entry ->
            val status = if (entry.id() in plan.keeperIds()) "Keep" else "Move to trash"
            val rename = plan.renameSteps().firstOrNull { it.keeperId() == entry.id() }
            items.put(JSONObject().put("id", entry.id()).put("name", entry.name())
                .put("kind", "review").put("path", entry.path())
                .put("subtitle", status + (rename?.let { " · Rename to ${it.newName()} after removal" } ?: ""))
                .put("sizeBytes", entry.size() ?: JSONObject.NULL)
                .put("modifiedAt", entry.modified()?.toEpochMilli())
                .put("createdAt", entry.created()?.toEpochMilli()))
        }
        next(page, actions, offset, limit, manifest.size)
        action(actions, "Cancel", "cancel") { cleanerPlan = null; view = "clean" }
        action(actions, "Back", "back") { cleanerPlan = null; view = "clean" }
        if (plan.readyForReview()) action(actions, "Delete", "confirm", destructive = true) {
            startJob("Moving reviewed cleanup files to trash", "browser") { cancel, _ ->
                val result = cleaner.executeReviewed(plan, cancel)
                synchronized(lock) { cleanerPlan = null }
                if (result.errors.isNotEmpty()) result.errors.joinToString("; ")
                else "Moved ${result.trash?.outcomes?.count { it.status.name == "SUCCESS" } ?: 0} to trash; ${result.renames.count { it.status.name == "SUCCESS" }} renamed"
            }
        }
    }

    private fun startJob(label: String, returnView: String,
        task: (Cancellation, Progress) -> String) {
        require(activeJobCancel == null && cleanScanCancel == null) { "Another file operation is running" }
        val cancel = AtomicBoolean(false)
        activeJobCancel = cancel
        jobReturnView = returnView
        jobStatus = label
        view = "progress"
        worker.execute {
            val outcome = runCatching {
                task(Cancellation { cancel.get() }, Progress { done, total, name ->
                    synchronized(lock) { jobStatus = "$label · $name · ${formatBytes(done)}${total?.let { " / ${formatBytes(it)}" } ?: ""}" }
                })
            }
            synchronized(lock) {
                activeJobCancel = null
                view = jobReturnView
                message = outcome.getOrElse { it.message ?: "Operation interrupted; review storage before retrying" }
                persist()
            }
        }
    }

    private fun renderProgress(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        page.put("state", "loading").put("summary", jobStatus).put("jobStatus", jobStatus)
        if (jobReturnView == "clean") {
            val findings = CleanerEngine.rankedFindings(cleanProgressEntries,
                cleanProgressGroups.values.toList(), cleanProgressSuggestions.values.toList())
            page.put("progressFor", "clean").put("complete", false)
                .put("cleanScopeId", cleanScanFolderId)
                .put("cleanScopeName", cleanScanFolderName)
                .put("cleanChecksSummary", cleanScanCategories.sortedBy { it.ordinal }.joinToString { cleanCategoryLabel(it) })
                .put("includeSubfolders", cleanScanIncludesSubfolders)
                .put("findingCount", findings.size)
                .put("foldersScanned", cleanProgressFolders).put("filesScanned", cleanProgressFiles)
            if (cleanProgressWarnings.isNotEmpty())
                page.put("message", cleanProgressWarnings.take(2).joinToString("; "))
            if (cleanProgressFiles > 0 || cleanProgressFolders > 0 || cleanProgressGroups.isNotEmpty()) {
                page.put("summary", "$jobStatus · Recommendations are preliminary")
                findings.drop(offset).take(limit).forEach { finding ->
                    items.put(cleanFindingItem(finding, cleanProgressEntries, preliminary = true))
                }
                next(page, actions, offset, limit, findings.size)
            }
        } else if (jobReturnView == "cleanReview") {
            page.put("title", "Preparing cleanup review").put("progressFor", "cleanReview")
        }
        action(actions, "Refresh progress", "refresh") { }
        action(actions, "Back to files", "back") {
            if (jobReturnView == "clean") {
                view = "browser"
            } else view = if (jobReturnView == "cleanReview") "clean" else jobReturnView
        }
        if (activeJobCancel != null || cleanScanCancel != null) action(actions, "Cancel operation", "cancel") {
            if (jobReturnView == "clean") cleanScanCancel?.set(true) else activeJobCancel?.set(true)
            jobStatus = "Cancel requested; finishing the current safe step"
            if (jobReturnView == "clean") view = "browser"
            else if (jobReturnView == "cleanReview") view = "clean"
        }
    }

    private fun renderArchive(page: JSONObject, actions: JSONArray) {
        val sourceId = archives.sourceId() ?: run { view = "browser"; return }
        val source = repository.get(sourceId)
        page.put("summary", "${source.name} · ${formatBytes(source.sizeBytes)} · ${source.relativePath}")
            .put("message", "Destination: ${archives.plannedDestinationPath() ?: "Choose a folder"}${if (archives.plannedNewFolderName() != null) " (new folder created after reviewed Extract)" else ""}. Extracts ZIP, 7z, RAR 4/5, TAR and TAR.GZ. Single-volume only; split ZIP and multipart RAR are unsupported. Password-protected archives work when their encryption method is supported. Wrong or unsupported passwords retain the source. Review every member and collision before extraction.")
        action(actions, "Back to files", "back") { archives.close(); view = "browser" }
        action(actions, "Choose destination", "destination") {
            archiveDestinationFolderId = archives.destinationId() ?: folderId
            view = "archiveDestination"
        }
        action(actions, "New folder name", "destination", input = "name",
            initial = archives.plannedNewFolderName() ?: "") { args ->
            archives.setNewFolderName(safeName(args.optString("name")))
        }
        action(actions, "Password", "password", input = "password") { args ->
            archives.setPassword(args.optString("password"))
            message = "Password updated in memory"
        }
        action(actions, if (archives.trashSourceAfterSuccess()) "Keep archive" else "Trash archive after success", "toggle") {
            archives.setTrashSourceAfterSuccess(!archives.trashSourceAfterSuccess())
        }
        action(actions, "Review archive", "review") {
            startJob("Inspecting archive", "archiveReview") { cancel, _ ->
                val result = archives.prepareReview(cancel)
                "Reviewed ${result.fileCount} files; ${result.blockers.size} issues require attention"
            }
        }
    }

    private fun renderArchiveReview(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val review = archives.currentReview() ?: run { view = "archive"; return }
        val source = review.inspection.source
        val members = review.inspection.members
        val collisions = review.destinationPreview.collisions.associateBy { it.memberPath }
        page.put("summary", "Source ${source.name} · ${formatBytes(source.sizeBytes)} · ${source.relativePath} · Modified ${source.modifiedAtMillis?.let { java.util.Date(it).toString() } ?: "Unavailable"} · Created ${source.createdAtMillis?.let { java.util.Date(it).toString() } ?: "Unavailable"}")
            .put("message", "Destination ${review.destinationPath}${if (review.newFolderName != null) " (new folder after reviewed Extract)" else ""} · ${review.fileCount} files · ${formatBytes(review.inspection.estimatedOutputBytes)} estimated · ${if (review.trashSourceAfterSuccess) "Archive moves to trash after success" else "Archive retained"}" +
                if (review.blockers.isNotEmpty()) " · ${review.blockers.size} issues listed below" else "")
            .put("complete", review.ready)
        action(actions, "Back", "back") { view = "archive" }
        action(actions, "Cancel", "cancel") { archives.close(); view = "browser" }
        if (review.ready) action(actions, "Extract", "confirm", destructive = true) {
            startJob("Extracting archive", "archiveReport") { cancel, progress ->
                val report = archives.executeReviewed(review.id, cancel, progress)
                synchronized(lock) { archiveReport = report }
                "${report.outcomes.count { it.status.name == "SUCCESS" }} extracted · ${report.sourceCleanupMessage ?: if (report.sourceMovedToTrash) "Archive moved to trash" else "Archive retained"}"
            }
        }
        review.blockers.drop(offset).take(limit).forEachIndexed { index, blocker ->
            items.put(JSONObject().put("id", "blocker:${offset + index}").put("name", blocker)
                .put("kind", "error").put("subtitle", "Resolve before extraction"))
        }
        val memberOffset = (offset - review.blockers.size).coerceAtLeast(0)
        members.drop(memberOffset).take((limit - (review.blockers.size - offset).coerceIn(0, limit)).coerceAtLeast(0)).forEach { member ->
            val collision = collisions[member.path]
            val chosen = review.collisionChoices[member.path]
            val row = JSONArray()
            if (collision != null) {
                for (choice in CollisionChoice.entries) {
                    action(row, if (chosen == choice) "${choice.name.lowercase()} ✓" else choice.name.lowercase(), "collision") {
                        archives.chooseCollision(member.path, choice)
                        message = "Collision choice updated"
                    }
                }
            }
            items.put(JSONObject().put("id", member.path).put("name", member.path)
                .put("kind", if (member.directory) "folder" else "file")
                .put("subtitle", if (collision != null) "Collision with ${collision.existing.relativePath}; ${chosen?.name ?: "choose action"}" else "New item")
                .put("sizeBytes", member.sizeBytes).put("actions", row))
        }
        next(page, actions, offset, limit, review.blockers.size + members.size)
    }

    private fun renderArchiveReport(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val report = archiveReport ?: run { view = "browser"; return }
        page.put("summary", "${report.outcomes.count { it.status.name == "SUCCESS" }} succeeded · ${report.outcomes.count { it.status.name != "SUCCESS" }} need attention · ${report.createdIds.size} created outputs")
            .put("message", report.sourceCleanupMessage ?: if (report.sourceMovedToTrash) "Archive moved to trash" else "Archive retained")
        action(actions, "Back to files", "back") { archives.close(); archiveReport = null; view = "browser" }
        val count = report.outcomes.size + report.createdIds.size
        report.outcomes.drop(offset).take(limit).forEach { outcome ->
            items.put(JSONObject().put("id", "outcome:${outcome.id}").put("name", outcome.id)
                .put("kind", "outcome").put("subtitle", "${outcome.status}: ${outcome.message ?: "Completed"}"))
        }
        val createdOffset = (offset - report.outcomes.size).coerceAtLeast(0)
        report.createdIds.drop(createdOffset).take((limit - (report.outcomes.size - offset).coerceIn(0, limit)).coerceAtLeast(0)).forEach { id ->
            items.put(JSONObject().put("id", "created:$id").put("name", runCatching { repository.get(id).name }.getOrDefault(id))
                .put("kind", "created").put("subtitle", "Created output; inspect before cleanup"))
        }
        next(page, actions, offset, limit, count)
    }

    private fun renderArchiveDestination(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val currentId = archiveDestinationFolderId ?: run { view = "archive"; return }
        val folder = repository.get(currentId)
        page.put("summary", "Choose existing destination folder · ${folder.relativePath}")
        action(actions, "Back to archive", "back") { view = "archive" }
        action(actions, "Locations", "locations") { view = "archiveLocations" }
        if (folder.parentId != null) action(actions, "Up", "navigate") { archiveDestinationFolderId = folder.parentId }
        action(actions, "Use this folder", "chooseDestination") { archives.setDestination(currentId); view = "archive" }
        val listed = repository.list(currentId, offset, limit).entries.filter { it.isDirectory }
        listed.forEach { child ->
            val row = JSONArray()
            action(row, "Open", "open") { archiveDestinationFolderId = child.id }
            items.put(JSONObject().put("id", child.id).put("name", child.name)
                .put("kind", "folder").put("path", child.relativePath)
                .put("folderAppearance", folderAppearance.json(child.id, child.name)).put("actions", row))
        }
        next(page, actions, offset, limit, repository.list(currentId, offset, limit).totalCount ?: offset + listed.size)
    }

    private fun preparePaste(destinationId: String) {
        val sources = clipboard.toList()
        require(sources.isNotEmpty()) { "Clipboard is empty" }
        pasteDestinationId = destinationId
        pasteChoice = CollisionChoice.SKIP
        startJob("Preparing paste review", "pasteReview") { cancel, _ ->
            val sourceEntries = sources.map { repository.get(it) }
            val sourceVersions = sourceEntries.associate { it.id to repository.fingerprint(it.id) }
            val destinationNames = linkedMapOf<String, FileEntry>()
            var offset = 0
            while (true) {
                if (cancel.isCanceled()) throw IllegalStateException("Review canceled")
                val page = repository.list(destinationId, offset, 100)
                page.entries.forEach { destinationNames[it.name] = it }
                val next = page.nextOffset ?: break
                require(next > offset && page.complete) { "Destination enumeration incomplete" }
                offset = next
            }
            val conflicts = sourceEntries.mapNotNull { source ->
                destinationNames[source.name]?.let { source.id to it }
            }.toMap()
            val conflictVersions = conflicts.values.associate { it.id to repository.fingerprint(it.id) }
            val plans = conflicts.values.distinctBy { it.id }.associate { it.id to repository.prepareDeletion(listOf(it.id)) }
            synchronized(lock) {
                pasteSources = sourceEntries
                pasteSourceFingerprints = sourceVersions
                pasteCollisions = conflicts
                pasteCollisionFingerprints = conflictVersions
                pasteReplacementPlans = plans
            }
            "${sourceEntries.size} items ready for review; ${conflicts.size} name collisions"
        }
    }

    private fun renderMovePicker(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val destination = destinationFolderId ?: run { view = "browser"; return }
        val folder = repository.get(destination)
        page.put("currentFolderPath", folder.relativePath).put("destinationWritable", folder.writable)
            .put("summary", "Moving ${clipboard.size} staged items")
        action(actions, "Back", "back") { view = "browser" }
        action(actions, "Cancel", "cancel") { clipboard = emptyList(); view = "browser" }
        action(actions, "Locations", "locations") { view = "moveLocations" }
        if (folder.parentId != null) action(actions, "Up", "up") { destinationFolderId = folder.parentId }
        if (folder.writable) action(actions, "Choose destination", "chooseDestination") { preparePaste(destination) }
        val listing = repository.list(destination, offset, limit)
        listing.entries.filter { it.isDirectory }.forEach { child ->
            val row = JSONArray()
            action(row, "Open", "nav") { destinationFolderId = child.id }
            items.put(JSONObject().put("id", child.id).put("name", child.name)
                .put("kind", "folder").put("path", child.relativePath)
                .put("folderAppearance", folderAppearance.json(child.id, child.name)).put("actions", row))
        }
        next(page, actions, offset, limit, listing.totalCount ?: offset + listing.entries.size)
    }

    private fun renderDestinationLocations(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val mode = view
        page.put("summary", "Choose a connected phone, SD card, USB drive, or granted folder")
        action(actions, "Back", "back") {
            view = when (mode) { "moveLocations" -> "movePicker"; "restoreLocations" -> "restoreDestination"; else -> "archiveDestination" }
        }
        val locations = repository.locations()
        locations.drop(offset).take(limit).forEach { location ->
            val row = JSONArray()
            if (location.available && location.writable) action(row, "Open", "open") {
                when (mode) {
                    "moveLocations" -> { destinationFolderId = location.rootId; view = "movePicker" }
                    "restoreLocations" -> { restoreDestinationFolderId = location.rootId; view = "restoreDestination" }
                    else -> { archiveDestinationFolderId = location.rootId; view = "archiveDestination" }
                }
            }
            items.put(JSONObject().put("id", location.id).put("name", location.title)
                .put("kind", "location").put("subtitle", if (location.available) {
                    if (location.writable) "Writable" else "Read only"
                } else "Reconnect required")
                .put("folderAppearance", folderAppearance.json(location.rootId, location.title))
                .put("actions", row))
        }
        next(page, actions, offset, limit, locations.size)
    }

    private fun renderPasteReview(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val destination = pasteDestinationId ?: run { view = "browser"; return }
        val rows = pasteSources + if (pasteChoice == CollisionChoice.REPLACE)
            pasteReplacementPlans.values.flatMap { it.roots + it.descendants }.distinctBy { it.id } else emptyList()
        val replaceReady = pasteReplacementPlans.values.all { it.complete && it.errors.isEmpty() }
        page.put("summary", "${pasteSources.size} ${if (clipboardMove) "move" else "copy"} items → ${repository.get(destination).relativePath} · ${pasteCollisions.size} collisions · Choice ${pasteChoice.name.lowercase()}")
            .put("complete", pasteChoice != CollisionChoice.REPLACE || replaceReady)
        if (!replaceReady && pasteChoice == CollisionChoice.REPLACE) page.put("message", "Replacement review incomplete; choose Skip or Keep both")
        action(actions, "Back", "back") { view = "browser" }
        action(actions, "Cancel", "cancel") { view = "browser" }
        if (pasteCollisions.isNotEmpty()) {
            for (choice in CollisionChoice.entries) action(actions, choice.name.lowercase(), "collision") { pasteChoice = choice }
        }
        if (pasteChoice != CollisionChoice.REPLACE || replaceReady) action(actions, "Paste", "confirm", destructive = true) {
            val current = linkedMapOf<String, FileEntry>()
            var at = 0
            while (true) {
                val listing = repository.list(destination, at, 100)
                listing.entries.forEach { current[it.name] = it }
                val next = listing.nextOffset ?: break
                require(next > at && listing.complete) { "Destination changed; review again" }
                at = next
            }
            for (source in pasteSources) {
                require(repository.fingerprint(source.id) == pasteSourceFingerprints[source.id]) { "Source changed; review again" }
                val prior = pasteCollisions[source.id]
                val now = current[source.name]
                require(prior?.id == now?.id) { "Destination changed; review again" }
                if (prior != null) require(repository.fingerprint(prior.id) == pasteCollisionFingerprints[prior.id]) {
                    "Destination file changed; review again"
                }
            }
            val sources = pasteSources.map { it.id }
            val move = clipboardMove
            val choice = pasteChoice
            val plans = if (choice == CollisionChoice.REPLACE) pasteReplacementPlans else emptyMap()
            startJob("Pasting ${sources.size} items", "browser") { cancel, progress ->
                val result = repository.copyOrMove(sources, destination, move, choice, cancel, progress,
                    replacementPlans = plans)
                synchronized(lock) {
                    if (move) clipboard = clipboard.filter { id -> result.outcomes.none { it.id == id && it.status.name == "SUCCESS" } }
                }
                "Pasted ${result.outcomes.count { it.status.name == "SUCCESS" }} of ${sources.size}; ${result.outcomes.count { it.status.name != "SUCCESS" }} need attention"
            }
        }
        rows.drop(offset).take(limit).forEach { entry ->
            val conflict = pasteCollisions[entry.id]
            items.put(entryJson(entry, false).put("subtitle", when {
                conflict != null -> "Collision: ${conflict.relativePath} · ${pasteChoice.name.lowercase()}"
                entry.id in pasteCollisionFingerprints -> "Existing destination to move to trash if replaced"
                else -> "${if (clipboardMove) "Move" else "Copy"} to destination"
            }))
        }
        next(page, actions, offset, limit, rows.size)
    }

    private fun renderSettings(page: JSONObject, actions: JSONArray) {
        page.put("summary", "Manage storage access, cleanup thresholds, and appearance.")
        action(actions, "Back", "back") {
            val cleanFolder = cleanSettingsFolderId
            cleanSettingsFolderId = null
            if (cleanFolder != null) {
                cleanPresentedFolderId = cleanFolder
                cleanPresentedKey = cleanKey(cleanFolder)
                cleanSetup = true
                view = "clean"
            } else view = if (folderId == null) "overview" else "browser"
        }
        action(actions, "Info", "info") { infoReturnView = view; view = "info" }
        action(actions, "Add folder", "setup", hostAction = "pickFolder") { }
        action(actions, if (android.os.Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) "Full access enabled" else "Enable full storage access", "setup",
            hostAction = "fullAccess") { }
        action(actions, "Large file threshold (MiB)", "setting", input = "value", initial = cleanerLargeMiB.toString()) { args ->
            cleanerLargeMiB = args.optString("value").toIntOrNull()?.coerceIn(1, 1_048_576)
                ?: throw IllegalArgumentException("Enter a positive MiB threshold")
        }
        action(actions, "Old download days", "setting", input = "value", initial = cleanerOldDays.toString()) { args ->
            cleanerOldDays = args.optString("value").toIntOrNull()?.coerceIn(1, 36500)
                ?: throw IllegalArgumentException("Enter a positive day count")
        }
        action(actions, "Clear ${cleanerExcludedIds.size} exclusions", "setting") { cleanerExcludedIds.clear() }
        action(actions, "Theme: ${preferences.getString("theme", "system")}", "setting") {
            val choices = listOf("system", "light", "dark", "amoled")
            val current = preferences.getString("theme", "system") ?: "system"
            preferences.edit().putString("theme", choices[(choices.indexOf(current).coerceAtLeast(0) + 1) % choices.size]).apply()
        }
    }

    private fun renderInfo(page: JSONObject, actions: JSONArray, items: JSONArray) {
        page.put("summary", "Storage access and recovery details")
        action(actions, "Back", "back") { view = infoReturnView }
        fun detail(name: String, value: String) {
            items.put(JSONObject().put("id", "info:$name").put("name", name)
                .put("kind", "detail").put("subtitle", value))
        }
        detail("Access", "Android still protects other apps’ private data. Reconnect removable or selected locations after a drive change or reinstall.")
        detail("Trash", "Files in this module’s same-storage trash occupy space until Empty trash. There is no automatic expiry.")
        detail("Recovery", "Provider trash and uninstall recovery vary by storage provider. Unavailable trash entries remain listed until reconnected.")
        val id = folderId ?: run { page.put("title", "Files info"); return }
        val folder = runCatching { repository.get(id) }.getOrElse {
            detail("Folder", "Unavailable. Reconnect its storage location to inspect it.")
            return
        }
        val location = repository.locations().firstOrNull { it.id == folder.locationId }
        val total = knownTotal(id)
        page.put("title", folder.name.ifBlank { location?.title ?: "Folder" })
            .put("summary", "Storage details for this folder")
            .put("currentFolder", folderAppearance.json(id, folder.name))
        detail("Path", folder.relativePath.ifBlank { "/" })
        detail("Location", location?.title ?: "Unavailable")
        detail("Folder size", if (total == null && id in pendingTotals) "Calculating…" else formatBytes(total?.bytes))
        if (total != null) {
            detail("Files", total.fileCount.toString())
            detail("Coverage", if (total.complete) "Complete" else "Partial")
        }
        totalErrors[id]?.let { detail("Size error", it) }
        detail("Capacity", formatBytes(location?.capacityBytes))
        detail("Free space", formatBytes(location?.freeBytes))
        if (location?.capacityBytes != null && location.capacityBytes > 0 && total != null) {
            detail("Share of location", "%.1f%%".format(total.bytes * 100.0 / location.capacityBytes))
        }
        detail("Access", when {
            !folder.readable -> "Unavailable"
            folder.writable -> "Read and write"
            else -> "Read only"
        })
    }

    private fun renderFolderAppearance(page: JSONObject, actions: JSONArray) {
        val id = appearanceFolderId ?: folderId ?: run { view = "overview"; return }
        val folder = repository.get(id)
        require(folder.isDirectory) { "Appearance belongs to a folder" }
        page.put("title", folder.name.ifBlank { "Folder appearance" })
            .put("summary", "Choose an icon and color for this folder")
            .put("folderAppearance", folderAppearance.json(id, folder.name))
            .put("folderAppearanceOptions", FolderAppearance.optionsJson())
        action(actions, "Back to folder", "back") { view = appearanceReturnView; appearanceFolderId = null }
        action(actions, "Choose icon", "setFolderIcon") { args ->
            folderAppearance.setIcon(id, args.optString("icon"))
        }
        action(actions, "Choose color", "setFolderColor") { args ->
            folderAppearance.setColor(id, args.optString("color"))
        }
        action(actions, "Reset appearance", "resetFolderAppearance") { folderAppearance.reset(id) }
    }

    private fun renderFilters(page: JSONObject, actions: JSONArray) {
        page.put("filter", JSONObject().put("query", query).put("includeSubfolders", includeSubfolders)
            .put("allLocations", allLocations).put("mimePrefix", mimePrefix)
            .put("modifiedAfter", modifiedAfter).put("modifiedBefore", modifiedBefore)
            .put("minSizeBytes", minSizeBytes).put("maxSizeBytes", maxSizeBytes))
        action(actions, "Back", "back") { view = if (query.isBlank()) "browser" else "search" }
        action(actions, "Apply filters", "applyFilter") { args ->
            query = args.optString("query").take(200)
            includeSubfolders = args.optBoolean("includeSubfolders", true)
            allLocations = args.optBoolean("allLocations", false)
            mimePrefix = args.optString("mimePrefix").takeIf { it.isNotBlank() }
            modifiedAfter = args.optLongOrNull("modifiedAfter")
            modifiedBefore = args.optLongOrNull("modifiedBefore")
            minSizeBytes = args.optLongOrNull("minSizeBytes")
            maxSizeBytes = args.optLongOrNull("maxSizeBytes")
            view = if (query.isNotBlank() || mimePrefix != null || modifiedAfter != null ||
                modifiedBefore != null || minSizeBytes != null || maxSizeBytes != null) "search" else "browser"
        }
    }

    private fun renderProperties(page: JSONObject, actions: JSONArray, items: JSONArray) {
        val id = propertyEntryId ?: run { view = "browser"; return }
        val entry = repository.get(id)
        page.put("title", entry.name).put("summary", entry.relativePath)
        action(actions, "Back", "back") { view = "browser" }
        items.put(entryJson(entry, false).put("subtitle", "${if (entry.isDirectory) "Folder" else entry.mimeType ?: "Unknown type"} · ${if (entry.writable) "Writable" else "Read only"}")
            .put("sizeBytes", entry.sizeBytes).put("modifiedAt", entry.modifiedAtMillis).put("createdAt", entry.createdAtMillis))
    }


    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name).takeIf { it >= 0 }

    private fun searchFilter() = SearchFilter(query, includeSubfolders, mimePrefix, modifiedAfter,
        modifiedBefore, minSizeBytes, maxSizeBytes)

    private fun searchRoots(folder: String): List<String> = if (allLocations)
        repository.locations().filter { it.available }.map { it.rootId } else listOf(folder)

    private fun renderDocument(page: JSONObject, actions: JSONArray) {
        val id = documentId ?: run { view = "browser"; return }
        val entry = runCatching { repository.get(id) }.getOrElse {
            page.put("state", "unavailable").put("message", "Document unavailable: ${it.message}")
            return
        }
        page.put("title", entry.name).put("document", JSONObject()
            .put("id", id).put("kind", documentKind).put("baseFingerprint", documentFingerprint))
        action(actions, "Back to files", "back") { view = "browser"; documentId = null; documentKind = null; documentFingerprint = null }
        if (view == "markdown") {
            action(actions, "Commit save", "documentCommit", destructive = true) {
                val staged = stagedDocumentWrites[id]
                when {
                    staged != null -> { repository.commitStagedWrite(staged); stagedDocumentWrites.remove(id) }
                    id in pendingSafWrites -> pendingSafWrites.remove(id)
                    else -> throw IllegalStateException("No pending document write to commit")
                }
                documentSaveStatus = "saved"
                documentFingerprint = repository.fingerprint(id)
            }
            action(actions, "Cancel pending save", "documentCancel", destructive = true) {
                stagedDocumentWrites.remove(id)?.let(repository::cancelStagedWrite)
                pendingSafWrites.remove(id)
                documentSaveStatus = "failed"
            }
        }
        action(actions, "Refresh document", "refresh") {
            documentFingerprint = repository.fingerprint(id)
        }
    }

    private fun renderHistory(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val history = repository.history()
        page.put("summary", "Recent file operations and per-item results")
        action(actions, "Back", "back") { view = if (folderId == null) "overview" else "browser" }
        if (history.any { !it.undone && it.kind in setOf("rename", "move", "trash") }) action(actions, "Undo last supported action", "undo", destructive = true) {
            startJob("Undoing last supported operation", "history") { cancel, _ ->
                if (cancel.isCanceled()) throw IllegalStateException("Undo canceled before it started")
                val outcome = repository.undoLast()
                "Undo: ${outcome.outcomes.count { it.status.name == "SUCCESS" }} succeeded; ${outcome.outcomes.count { it.status.name != "SUCCESS" }} need review"
            }
        }
        history.drop(offset).take(limit).forEach { entry ->
            val row = JSONArray()
            action(row, "Details", "open") { historyDetailId = entry.id; view = "historyDetail" }
            items.put(JSONObject().put("id", entry.id).put("name", entry.kind)
                .put("kind", "history").put("subtitle", "${java.util.Date(entry.atMillis)} · ${entry.outcomes.size} outcomes" +
                    if (entry.undone) " · undone" else "")
                .put("actions", row))
        }
        next(page, actions, offset, limit, history.size)
    }

    private fun renderHistoryDetail(page: JSONObject, actions: JSONArray, items: JSONArray, offset: Int, limit: Int) {
        val entry = repository.history().firstOrNull { it.id == historyDetailId }
            ?: run { view = "history"; return }
        page.put("summary", "${entry.kind} · ${java.util.Date(entry.atMillis)} · ${entry.outcomes.size} item outcomes${if (entry.undone) " · undone" else ""}")
        action(actions, "Back to history", "back") { view = "history" }
        entry.outcomes.drop(offset).take(limit).forEachIndexed { index, outcome ->
            val row = JSONArray()
            action(row, "Review current state", "retry") {
                val candidate = listOfNotNull(outcome.resultingId, outcome.id).firstNotNullOfOrNull { id ->
                    runCatching { repository.get(id) }.getOrNull()
                }
                if (candidate != null) {
                    locationId = candidate.locationId
                    folderId = if (candidate.isDirectory) candidate.id else candidate.parentId
                    view = "browser"
                    message = "Review current files before starting a new operation; history is never replayed automatically"
                } else {
                    view = "trash"
                    message = "Source unavailable. Reconnect its location or review Trash before retrying"
                }
            }
            items.put(JSONObject().put("id", "${entry.id}:${offset + index}")
                .put("name", outcome.id).put("kind", "outcome")
                .put("subtitle", "${outcome.status} · ${outcome.message ?: "No further detail"}" +
                    (outcome.resultingId?.let { " · Result $it" } ?: ""))
                .put("actions", row))
        }
        next(page, actions, offset, limit, entry.outcomes.size)
    }

    private fun next(page: JSONObject, actions: JSONArray, offset: Int, limit: Int, count: Int) {
        if (offset > 0) action(actions, "Previous page", "previous") {
            currentOffset = (offset - limit).coerceAtLeast(0); scrollAnchor = 0; scrollOffset = 0
        }
        if (offset + limit < count) {
            page.put("nextCursor", (offset + limit).toString())
            action(actions, "Load more", "loadMore") { currentOffset = offset + limit; scrollAnchor = 0; scrollOffset = 0 }
        } else page.put("nextCursor", JSONObject.NULL)
    }

    private fun action(out: JSONArray, label: String, kind: String, input: String? = null,
        initial: String = "", destructive: Boolean = false, hostAction: String? = null,
        documentId: String? = null, handler: (JSONObject) -> Unit) {
        out.put(registered(label, kind, input, initial, destructive, hostAction, documentId, handler))
    }

    private fun registered(label: String, kind: String, input: String? = null,
        initial: String = "", destructive: Boolean = false, hostAction: String? = null,
        documentId: String? = null, handler: (JSONObject) -> Unit): JSONObject {
        val id = UUID.randomUUID().toString()
        commands[id] = Command(revision, kind, destructive, hostAction, documentId, handler)
        if (kind == "scroll") {
            scrollCommands[id] = ScrollCommand(revision, scrollKey())
            while (scrollCommands.size > 512) scrollCommands.remove(scrollCommands.keys.first())
        }
        if (kind == "sizeProgress" && folderId != null) {
            sizeProgressCommands[id] = SizeProgressCommand(revision, requireNotNull(folderId))
            while (sizeProgressCommands.size > 512) sizeProgressCommands.remove(sizeProgressCommands.keys.first())
        }
        return JSONObject().put("id", id).put("label", label).put("kind", kind)
            .put("input", input).put("initial", initial).put("hostAction", hostAction).put("documentId", documentId)
    }

    private fun safeName(raw: String): String = raw.trim().also {
        require(it.isNotEmpty() && it.length <= 255 && !it.contains('/') && !it.contains('\\')) { "Invalid name" }
    }

    private fun textFileName(rawName: String, rawExtension: String): Pair<String, String> {
        val base = safeName(rawName)
        require(base != "." && base != ".." && base.none { Character.isISOControl(it) }) { "Invalid file name" }
        val extension = rawExtension.trim().removePrefix(".")
        require(extension.length <= 16 && extension.all { it.isLetterOrDigit() && it.code < 128 }) {
            "Extension must contain letters or numbers only"
        }
        val fullName = if (extension.isBlank() || base.endsWith(".$extension", ignoreCase = true)) base
            else "$base.$extension"
        require(fullName.length <= 255) { "File name is too long" }
        val mime = if (extension.equals("md", true) || extension.equals("markdown", true))
            "text/markdown" else "text/plain"
        return fullName to mime
    }

    private fun persist() {
        preferences.edit().putString("view", when (view) {
            "review", "emptyTrashReview", "cleanReview", "clean", "info", "folderAppearance", "settings", "keeper", "progress", "archiveReview", "archiveReport", "archiveDestination", "historyDetail" -> if (folderId == null) "overview" else "browser"
            else -> view
        })
            .putString("location", locationId).putString("folder", folderId).putString("query", query)
            .putString("sort", sort.name).putBoolean("hidden", showHidden)
            .putInt("currentOffset", currentOffset).putInt("scrollAnchor", scrollAnchor)
            .putInt("scrollOffset", scrollOffset)
            .putBoolean("grid", grid)
            .putBoolean("subfolders", includeSubfolders).putBoolean("allLocations", allLocations)
            .putString("mimePrefix", mimePrefix)
            .putLong("modifiedAfter", modifiedAfter ?: -1).putLong("modifiedBefore", modifiedBefore ?: -1)
            .putLong("minSizeBytes", minSizeBytes ?: -1).putLong("maxSizeBytes", maxSizeBytes ?: -1)
            .putBoolean("cleanSubfolders", cleanerIncludeSubfolders)
            .putInt("cleanLargeMiB", cleanerLargeMiB).putInt("cleanOldDays", cleanerOldDays)
            .putStringSet("cleanChecks", cleanerCategories.map { it.name }.toSet())
            .putStringSet("cleanExcludedIds", cleanerExcludedIds.toSet())
            .putStringSet("favorites", favorites.toSet()).putString("recent", recent.joinToString(","))
            .putString("documentId", documentId)
            .putString("documentKind", documentKind).putString("documentFingerprint", documentFingerprint).apply()
    }

    private fun errorPage(error: String): String = JSONObject().put("version", 1).put("kind", "files")
        .put("title", "Files").put("view", view).put("state", "error").put("message", error)
        .put("items", JSONArray()).put("actions", JSONArray()).toString()

    private fun formatBytes(value: Long?): String {
        if (value == null) return "Size unavailable"
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var number = value.toDouble(); var unit = -1
        do { number /= 1024; unit++ } while (number >= 1024 && unit < units.lastIndex)
        return "%.1f %s".format(number, units[unit])
    }

    private fun scrollKey(): String = MessageDigest.getInstance("SHA-256")
        .digest("${locationId.orEmpty()}|${folderId.orEmpty()}|$view".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
