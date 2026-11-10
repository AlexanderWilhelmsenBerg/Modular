package io.github.alexanderwilhelmsenberg.modular.addon.files.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.system.Os
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Real filesystem and DocumentsProvider implementation. IDs encode a grant key and a verified ancestry chain. */
class AndroidFilesRepository(private val context: Context) : FilesRepository {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("files_storage_v1", Context.MODE_PRIVATE)
    private val totals = ConcurrentHashMap<String, DirectoryTotal>()
    private data class PendingWrite(val id: String, val expectedFingerprint: String, val temp: File, val descriptor: ParcelFileDescriptor)
    private companion object {
        val pendingWrites = ConcurrentHashMap<String, PendingWrite>()
        val mutationLock = ReentrantLock()
    }
    private val trashName = ".ModularFilesTrash"
    private val maxPage = 500
    private val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
    )

    private data class Root(val key: String, val label: String, val tree: Uri?, val file: File?, val kind: StorageLocation.Kind, val writable: Boolean)
    private data class Node(val root: Root, val id: String, val parentId: String?, val name: String, val relative: String, val directory: Boolean, val mime: String?, val size: Long?, val modified: Long?, val created: Long?, val writable: Boolean, val file: File?, val uri: Uri?) {
        fun entry() = FileEntry(id, root.key, parentId, name, relative, directory, mime, size, modified, created, true, writable)
    }
    private class PartialReplacement(message: String, val recoveryIds: List<String>, cause: Throwable) : IllegalStateException(message, cause)

    override fun locations(): List<StorageLocation> = roots().map { root ->
        val available = try { resolve(rootId(root)); true } catch (_: Exception) { false }
        val volume = root.file
        StorageLocation(root.key, root.label, rootId(root), root.kind, available, root.writable && available,
            volume?.totalSpace?.takeIf { it > 0 }, volume?.freeSpace?.takeIf { it >= 0 })
    }

    override fun addSelectedFolder(uri: Uri, grantFlags: Int): StorageLocation {
        mutationLock.lock()
        try {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "Select a document tree" }
        val flags = grantFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "Read grant required" }
        resolver.takePersistableUriPermission(uri, flags)
        val key = "s" + digest(uri.toString().toByteArray()).take(24)
        val doc = DocumentsContract.getTreeDocumentId(uri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(uri, doc)
        val info = query(rootUri)
        require(info.directory) { "Selected location must be a folder" }
        prefs.edit().putString("tree.$key", uri.toString()).putString("label.$key", info.name)
            .putBoolean("write.$key", flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0).apply()
        return locations().first { it.id == key }
        } finally { mutationLock.unlock() }
    }

    override fun removeSelectedFolder(locationId: String) {
        mutationLock.lock()
        try {
        val raw = prefs.getString("tree.$locationId", null) ?: return
        val tree = Uri.parse(raw)
        try { resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        try { resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { }
        prefs.edit().remove("tree.$locationId").remove("label.$locationId").remove("write.$locationId").apply()
        } finally { mutationLock.unlock() }
    }

    override fun list(folderId: String, offset: Int, limit: Int, sort: FileSort, showHidden: Boolean): FilePage {
        require(offset >= 0 && limit in 1..maxPage)
        val node = resolve(folderId)
        require(node.directory) { "Not a folder" }
        val children = children(node).filter { it.name != trashName && (showHidden || !it.name.startsWith('.')) }.map { it.entry() }
        val comparator = when (sort) {
            FileSort.NAME -> compareBy<FileEntry> { it.name.lowercase() }
            FileSort.MODIFIED -> compareByDescending<FileEntry> { it.modifiedAtMillis ?: Long.MIN_VALUE }
            FileSort.SIZE -> compareByDescending<FileEntry> { it.sizeBytes ?: -1L }
            FileSort.TYPE -> compareBy<FileEntry> { it.mimeType ?: "" }
        }.thenBy { it.name.lowercase() }
        val ordered = children.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.then(comparator))
        val slice = ordered.drop(offset).take(limit)
        val next = (offset + slice.size).takeIf { it < ordered.size }
        return FilePage(slice, next, ordered.size, true)
    }

    override fun get(id: String): FileEntry = resolve(id).entry()

    override fun search(folderIds: List<String>, filter: SearchFilter, cancel: Cancellation): SearchPage = searchPage(folderIds, filter, 0, 100, cancel)

    override fun searchPage(folderIds: List<String>, filter: SearchFilter, offset: Int, limit: Int, cancel: Cancellation): SearchPage {
        require(offset >= 0 && limit in 1..maxPage)
        val found = ArrayList<FileEntry>()
        var matched = 0
        val errors = ArrayList<String>()
        val locations = LinkedHashSet<String>()
        val seen = HashSet<String>()
        val queue = ArrayDeque<Pair<Node, Int>>()
        folderIds.forEach { id -> try { queue.add(resolve(id) to 0) } catch (e: Exception) { errors.add("$id: ${e.message}") } }
        while (queue.isNotEmpty() && !cancel.isCanceled()) {
            val (folder, depth) = queue.removeFirst()
            if (!seen.add(nodeIdentity(folder))) { errors.add("Cyclic or repeated folder: ${folder.relative}"); continue }
            locations.add(folder.root.key)
            try {
                children(folder).forEach { child ->
                    if (child.name == trashName) return@forEach
                    if (matches(child, filter)) {
                        if (matched >= offset && matched < offset + limit) found.add(child.entry())
                        matched++
                    }
                    if (child.directory && filter.includeSubfolders && depth < 128) queue.add(child to depth + 1)
                }
            } catch (e: Exception) { errors.add("${folder.relative}: ${e.message}") }
        }
        return SearchPage(found, locations.toList(), errors.isEmpty() && !cancel.isCanceled(), errors,
            (offset + found.size).takeIf { it < matched }, matched)
    }

    override fun directoryTotal(folderId: String, cancel: Cancellation): DirectoryTotal {
        val root = resolve(folderId)
        require(root.directory)
        var bytes = 0L
        var files = 0L
        var complete = true
        val queue = ArrayDeque<Node>()
        queue.add(root)
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            if (cancel.isCanceled()) { complete = false; break }
            val folder = queue.removeFirst()
            if (!seen.add(nodeIdentity(folder))) { complete = false; continue }
            try {
                children(folder).forEach { child ->
                    if (child.name == trashName) return@forEach
                    if (child.directory) queue.add(child) else {
                        files++
                        if (child.size == null) complete = false else bytes = safeAdd(bytes, child.size)
                    }
                }
            } catch (_: Exception) { complete = false }
        }
        val value = DirectoryTotal(bytes, files, complete, System.currentTimeMillis())
        totals[folderId] = value
        return value
    }

    override fun cachedDirectoryTotal(folderId: String): DirectoryTotal? = totals[folderId]

    override fun openRead(id: String): ParcelFileDescriptor {
        val node = resolve(id)
        require(!node.directory) { "Cannot open a folder" }
        return if (node.file != null) ParcelFileDescriptor.open(node.file, ParcelFileDescriptor.MODE_READ_ONLY)
        else resolver.openFileDescriptor(requireNotNull(node.uri), "r") ?: error("Provider did not open file")
    }

    override fun openWrite(id: String, expectedFingerprint: String): ParcelFileDescriptor {
        val node = resolve(id)
        require(!node.directory && node.writable) { "File is read only" }
        require(fingerprint(id) == expectedFingerprint) { "File changed; review before saving" }
        require(node.file == null) { "Direct files require staged write and explicit commit" }
        // SAF providers may only support in-place writes. The editor must disclose this and retain its draft.
        return resolver.openFileDescriptor(requireNotNull(node.uri), "wt") ?: error("Provider did not open file for writing")
    }

    override fun beginStagedWrite(id: String, expectedFingerprint: String): StagedWrite {
        mutationLock.lock()
        try {
        val node = resolve(id)
        require(!node.directory && node.writable && node.file != null) { "Staged write requires a writable direct file" }
        require(fingerprint(id) == expectedFingerprint) { "File changed; review before saving" }
        val token = UUID.randomUUID().toString()
        val temp = File(requireNotNull(node.file.parentFile), ".${token}.modular-save")
        startJournal(token, "stagedWrite", listOf(id), temp.path)
        try {
            require(temp.createNewFile()) { "Could not create save staging file" }
            val descriptor = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
            pendingWrites[token] = PendingWrite(id, expectedFingerprint, temp, descriptor)
            return StagedWrite(token, descriptor)
        } catch (e: Exception) {
            temp.delete()
            finishJournal(token)
            throw e
        }
        } finally { mutationLock.unlock() }
    }

    override fun commitStagedWrite(token: String): FileEntry {
        mutationLock.lock()
        try {
        val pending = pendingWrites[token] ?: error("Save session unavailable; original file was not changed")
        // The authenticated host commits only after its copy of the descriptor closes.
        pending.descriptor.close()
        val current = resolve(pending.id)
        require(fingerprint(current) == pending.expectedFingerprint) { "Original changed during edit; staged copy retained for review" }
        val original = requireNotNull(current.file)
        require(pending.temp.isFile) { "Staged data unavailable" }
        FileOutputStream(pending.temp, true).use { it.fd.sync() }
        try {
            java.nio.file.Files.move(pending.temp.toPath(), original.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            throw IllegalStateException("Atomic save unsupported or failed; original preserved and staged copy retained", e)
        }
        val result = resolve(pending.id).entry()
        pendingWrites.remove(token)
        recordOperation(token, "save", listOf(FileOutcome(pending.id, FileOutcome.Status.SUCCESS, resultingId = result.id)))
        finishJournal(token)
        return result
        } finally { mutationLock.unlock() }
    }

    override fun cancelStagedWrite(token: String) {
        mutationLock.lock()
        try {
        val pending = pendingWrites.remove(token) ?: return
        try { pending.descriptor.close() } catch (_: Exception) { }
        require(pending.temp.delete() || !pending.temp.exists()) { "Could not remove staged copy" }
        recordOperation(token, "cancelSave", listOf(FileOutcome(pending.id, FileOutcome.Status.CANCELED)))
        finishJournal(token)
        } finally { mutationLock.unlock() }
    }

    override fun fingerprint(id: String): String {
        val node = resolve(id)
        return fingerprint(node)
    }

    override fun scopedContent(id: String): ScopedContent {
        val node = resolve(id)
        require(!node.directory)
        val uri = node.uri ?: FileProvider.getUriForFile(context, "${context.packageName}.files", requireNotNull(node.file))
        return ScopedContent(uri, node.mime, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun createDirectory(parentId: String, name: String): FileEntry = mutationLock.withLock { create(resolve(parentId), name, DocumentsContract.Document.MIME_TYPE_DIR).entry() }
    override fun createFile(parentId: String, name: String, mimeType: String): FileEntry = mutationLock.withLock { create(resolve(parentId), name, mimeType).entry() }

    override fun rename(id: String, newName: String): FileEntry {
        mutationLock.lock()
        try {
        validName(newName)
        val node = resolve(id)
        require(node.parentId != null && node.writable) { "Cannot rename this location" }
        val parent = resolve(node.parentId)
        require(children(parent).none { it.name == newName }) { "A file with that name exists" }
        val operation = UUID.randomUUID().toString()
        startJournal(operation, "rename", listOf(id), parent.id)
        val renamed = if (node.file != null) {
            val target = File(requireNotNull(parent.file), newName)
            java.nio.file.Files.move(node.file.toPath(), target.toPath())
            resolve(directChildId(parent.id, newName)).entry()
        } else {
            val newUri = DocumentsContract.renameDocument(resolver, requireNotNull(node.uri), newName) ?: error("Provider rejected rename")
            val docId = DocumentsContract.getDocumentId(newUri)
            resolve(safChildId(parent.id, docId)).entry()
        }
        recordOperation(operation, "rename", listOf(FileOutcome(id, FileOutcome.Status.SUCCESS, node.name, renamed.id, resultFingerprint = fingerprint(renamed.id))))
        finishJournal(operation)
        return renamed
        } finally { mutationLock.unlock() }
    }

    override fun copyOrMove(ids: List<String>, destinationId: String, move: Boolean, collision: CollisionChoice, cancel: Cancellation, progress: Progress, replacementPlans: Map<String, DeletionPlan>): OperationResult {
        mutationLock.lock()
        try {
        val operation = UUID.randomUUID().toString()
        val outcomes = ArrayList<FileOutcome>()
        val destination = resolve(destinationId)
        require(destination.directory && destination.writable) { "Destination is not writable" }
        val sources = ids.distinct().map { resolve(it) }
        startJournal(operation, if (move) "move" else "copy", sources.map { it.id }, destination.id)
        val total = sources.sumOf { it.size ?: 0L }
        var done = 0L
        for (source in sources) {
            if (cancel.isCanceled()) { outcomes.add(FileOutcome(source.id, FileOutcome.Status.CANCELED)); continue }
            try {
                require(source.parentId != null) { "Cannot copy a storage root" }
                require(source.id != destination.id && (source.root.key != destination.root.key || !destination.relative.startsWith(source.relative.trimEnd('/') + "/"))) { "Cannot copy a folder into itself" }
                val sourcePlan = if (move) prepareDeletion(listOf(source.id), cancel) else null
                if (sourcePlan != null) require(sourcePlan.complete) { "Source could not be fully reviewed" }
                val needed = if (source.directory) directoryTotal(source.id, cancel).let { if (it.complete) it.bytes else null } else source.size
                val knownFree = destination.root.file?.usableSpace?.takeIf { it >= 0 }
                if (needed != null && knownFree != null) require(needed <= knownFree) { "Insufficient free space at destination" }
                val target = copyNode(source, destination, collision, cancel, { count -> done += count; progress.onProgress(done, total, source.name) }, replacementPlans = replacementPlans)
                if (target == null) { outcomes.add(FileOutcome(source.id, FileOutcome.Status.SKIPPED)); continue }
                if (move) {
                    val trashed = moveToTrash(requireNotNull(sourcePlan), cancel, progress)
                    if (trashed.outcomes.any { it.status != FileOutcome.Status.SUCCESS }) {
                        outcomes.add(FileOutcome(source.id, FileOutcome.Status.PARTIAL, "Copy verified; source retained or trash incomplete", target.id))
                        continue
                    }
                    outcomes.add(FileOutcome(source.id, FileOutcome.Status.SUCCESS, resultingId = target.id,
                        recoveryIds = trashed.outcomes.mapNotNull { it.resultingId }, resultFingerprint = fingerprint(target.id)))
                    continue
                }
                outcomes.add(FileOutcome(source.id, FileOutcome.Status.SUCCESS, resultingId = target.id))
            } catch (e: PartialReplacement) {
                outcomes.add(FileOutcome(source.id, FileOutcome.Status.PARTIAL, e.message, recoveryIds = e.recoveryIds))
            } catch (e: Exception) {
                outcomes.add(FileOutcome(source.id, FileOutcome.Status.FAILED, e.message))
            }
        }
        recordOperation(operation, "move".takeIf { move } ?: "copy", outcomes)
        finishJournal(operation)
        return OperationResult(operation, outcomes)
        } finally { mutationLock.unlock() }
    }

    override fun prepareDeletion(ids: List<String>, cancel: Cancellation): DeletionPlan {
        val roots = ArrayList<FileEntry>()
        val manifest = LinkedHashMap<String, FileEntry>()
        val fingerprints = LinkedHashMap<String, String>()
        val dirChildren = LinkedHashMap<String, Set<String>>()
        val errors = ArrayList<String>()
        val selected = ids.distinct().mapNotNull { id -> try { resolve(id) } catch (e: Exception) { errors.add("$id: ${e.message}"); null } }
        val selectedIds = selected.map { it.id }.toSet()
        selected.filter { node -> generateSequence(node.parentId) { parent -> try { resolve(parent).parentId } catch (_: Exception) { null } }.none { it in selectedIds } }.forEach { roots.add(it.entry()) }
        val seen = HashSet<String>()
        fun visit(node: Node) {
            if (cancel.isCanceled()) { errors.add("Canceled"); return }
            if (node.name == trashName || node.parentId == null) { errors.add("Protected location: ${node.relative}"); return }
            if (!seen.add(nodeIdentity(node))) { errors.add("Cyclic or repeated item: ${node.relative}"); return }
            if (node.directory && node.root.tree != null) {
                errors.add("${node.relative}: provider cannot safely delete a folder if a child is added after review")
            }
            try {
                val fp = fingerprint(node)
                manifest[node.id] = node.entry()
                fingerprints[node.id] = fp
                if (node.directory) {
                    val kids = children(node)
                    dirChildren[node.id] = kids.map { it.id }.toSet()
                    kids.forEach(::visit)
                }
            } catch (e: Exception) { errors.add("${node.relative}: ${e.message}") }
        }
        roots.forEach { root -> try { visit(resolve(root.id)) } catch (e: Exception) { errors.add("${root.relativePath}: ${e.message}") } }
        val all = manifest.values.toList()
        return DeletionPlan(UUID.randomUUID().toString(), roots, all, all.filter { !it.isDirectory }.sumOf { it.sizeBytes ?: 0L }, all.count { !it.isDirectory }.toLong(), errors.isEmpty(), errors, fingerprints, dirChildren)
    }

    override fun moveToTrash(plan: DeletionPlan, cancel: Cancellation, progress: Progress): OperationResult {
        mutationLock.lock()
        try {
        require(plan.complete && plan.errors.isEmpty()) { "Incomplete review" }
        startJournal(plan.id, "trash", plan.roots.map { it.id }, null)
        val outcomes = ArrayList<FileOutcome>()
        val groups = plan.roots.map { root -> plan.descendants.filter { it.locationId == root.locationId && (it.id == root.id || it.relativePath.startsWith(root.relativePath.trimEnd('/') + "/")) } }
        var movedBytes = 0L
        for (group in groups) {
            if (cancel.isCanceled()) { group.forEach { outcomes.add(FileOutcome(it.id, FileOutcome.Status.CANCELED)) }; continue }
            val error = validateGroup(group, plan.fingerprints, plan.directoryChildren)
            if (error != null) { group.forEach { outcomes.add(FileOutcome(it.id, FileOutcome.Status.CHANGED, error)) }; continue }
            val files = group.filter { !it.isDirectory }.sortedByDescending { it.relativePath.length }
            var failed = false
            val removedIds = HashSet<String>()
            for (item in files) {
                if (failed) { outcomes.add(FileOutcome(item.id, FileOutcome.Status.SKIPPED, "Group needs fresh review")); continue }
                if (cancel.isCanceled()) { failed = true; outcomes.add(FileOutcome(item.id, FileOutcome.Status.CANCELED)); continue }
                try {
                    val changed = remainingChildrenChanged(group, item.id, plan.directoryChildren, removedIds)
                    require(changed == null) { changed ?: "Folder changed" }
                    val current = resolve(item.id)
                    require(fingerprint(current) == plan.fingerprints[item.id]) { "File changed" }
                    val record = trashOne(current, cancel) { n -> movedBytes += n; progress.onProgress(movedBytes, plan.totalKnownBytes, item.name) }
                    outcomes.add(FileOutcome(item.id, FileOutcome.Status.SUCCESS, resultingId = record.id))
                    removedIds.add(item.id)
                } catch (e: Exception) { failed = true; outcomes.add(FileOutcome(item.id, FileOutcome.Status.FAILED, e.message)) }
            }
            group.filter { it.isDirectory }.sortedByDescending { it.relativePath.length }.forEach { item ->
                if (failed || cancel.isCanceled()) { outcomes.add(FileOutcome(item.id, FileOutcome.Status.SKIPPED, "Descendant work incomplete")); return@forEach }
                try {
                    val changed = remainingChildrenChanged(group, item.id, plan.directoryChildren, removedIds)
                    require(changed == null) { changed ?: "Folder changed" }
                    val current = resolve(item.id)
                    require(children(current).isEmpty()) { "Folder has new or unreviewed children" }
                    val record = trashEmptyFolder(current)
                    outcomes.add(FileOutcome(item.id, FileOutcome.Status.SUCCESS, resultingId = record.id))
                    removedIds.add(item.id)
                } catch (e: Exception) { failed = true; outcomes.add(FileOutcome(item.id, FileOutcome.Status.FAILED, e.message)) }
            }
        }
        recordOperation(plan.id, "trash", outcomes)
        finishJournal(plan.id)
        return OperationResult(plan.id, outcomes)
        } finally { mutationLock.unlock() }
    }

    override fun trashEntries(): List<TrashEntry> {
        mutationLock.lock()
        try {
        val cached = readTrashCache().associateBy { it.id }.toMutableMap()
        for (root in roots()) {
            try {
                val folder = findTrash(root) ?: continue
                children(folder).filter { it.name.endsWith(".meta.json") }.forEach { meta ->
                    try {
                        val record = readJson(meta)
                        val id = record.getString("id")
                        val payloadName = record.getString("payload")
                        val payload = children(folder).firstOrNull { it.name == payloadName }
                        if (payload != null || runCatching { resolve(record.optString("originalId")); false }.getOrDefault(true)) {
                            cached[id] = recordToTrash(record, root.key, payload != null)
                        } else cached.remove(id)
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { /* Offline records stay in cache. */ }
        }
        val result = cached.values.map { it.copy(available = try { locatePayload(it) != null } catch (_: Exception) { false }) }.sortedByDescending { it.trashedAtMillis }
        saveTrashCache(result)
        return result
        } finally { mutationLock.unlock() }
    }

    override fun trashSummary(): TrashSummary {
        val entries = trashEntries()
        return TrashSummary(entries.count { !it.isDirectory }.toLong(), entries.count { it.isDirectory }.toLong(), entries.sumOf { it.sizeBytes ?: 0L }, entries.count { !it.available }.toLong())
    }

    override fun restore(ids: List<String>, choices: Map<String, RestoreChoice>, cancel: Cancellation): OperationResult {
        mutationLock.lock()
        try {
        val operation = UUID.randomUUID().toString()
        startJournal(operation, "restore", ids, null)
        val records = trashEntries().associateBy { it.id }
        val outcomes = ArrayList<FileOutcome>()
        for (id in ids.distinct().sortedWith(compareBy<String> { if (records[it]?.isDirectory == true) 0 else 1 }.thenBy { records[it]?.originalPath?.count { c -> c == '/' } ?: Int.MAX_VALUE })) {
            if (cancel.isCanceled()) { outcomes.add(FileOutcome(id, FileOutcome.Status.CANCELED)); continue }
            val record = records[id]
            if (record == null) { outcomes.add(FileOutcome(id, FileOutcome.Status.FAILED, "Trash entry missing")); continue }
            var restoredTarget: Node? = null
            try {
                val source = locatePayload(record) ?: error("Storage unavailable or trashed item missing")
                require(!(source.directory && source.root.tree != null)) { "Provider cannot safely remove a restored trash folder" }
                val choice = choices[id] ?: RestoreChoice(CollisionChoice.SKIP)
                val parent = choice.alternateParentId?.let(::resolve) ?: ensureOriginalParent(record)
                require(parent.writable) { "Restore destination is read only" }
                val target = copyNode(source, parent, choice.collision, cancel, {}, record.originalName, choice.replacementPlans)
                if (target == null) { outcomes.add(FileOutcome(id, FileOutcome.Status.SKIPPED, "Name collision")); continue }
                restoredTarget = target
                removeTrashPayloadAndMeta(record)
                outcomes.add(FileOutcome(id, FileOutcome.Status.SUCCESS, resultingId = target.id))
            } catch (e: PartialReplacement) { outcomes.add(FileOutcome(id, FileOutcome.Status.PARTIAL, e.message, recoveryIds = e.recoveryIds)) }
            catch (e: Exception) { outcomes.add(FileOutcome(id, if (restoredTarget != null) FileOutcome.Status.PARTIAL else FileOutcome.Status.FAILED, e.message, restoredTarget?.id)) }
        }
        recordOperation(operation, "restore", outcomes)
        finishJournal(operation)
        return OperationResult(operation, outcomes)
        } finally { mutationLock.unlock() }
    }

    override fun prepareEmptyTrash(ids: List<String>): EmptyTrashPlan {
        val selected = trashEntries().filter { it.id in ids.toSet() }
        val errors = selected.filter { !it.available }.map { "${it.originalPath}: storage unavailable" }.toMutableList()
        ids.distinct().filter { wanted -> selected.none { it.id == wanted } }.forEach { errors.add("$it: trash entry missing") }
        val manifest = ArrayList<FileEntry>()
        val fingerprints = LinkedHashMap<String, String>()
        selected.filter { it.available }.forEach { item ->
            try {
                val payload = locatePayload(item) ?: error("Payload missing")
                require(!(payload.directory && payload.root.tree != null)) { "Provider cannot safely empty a folder in trash" }
                val seen = HashSet<String>()
                fun visit(node: Node) {
                    require(seen.add(nodeIdentity(node))) { "Cyclic or repeated trash item" }
                    manifest.add(node.entry())
                    fingerprints[node.id] = fingerprint(node)
                    if (node.directory) children(node).forEach(::visit)
                }
                visit(payload)
            } catch (e: Exception) { errors.add(e.message ?: "Cannot enumerate trash") }
        }
        return EmptyTrashPlan(selected, manifest, errors.isEmpty(), errors, fingerprints)
    }

    override fun emptyTrash(plan: EmptyTrashPlan, cancel: Cancellation): OperationResult {
        mutationLock.lock()
        try {
        require(plan.complete) { "Incomplete trash review" }
        val operation = UUID.randomUUID().toString()
        startJournal(operation, "emptyTrash", plan.entries.map { it.id }, null)
        val outcomes = ArrayList<FileOutcome>()
        for (record in plan.entries) {
            if (cancel.isCanceled()) { outcomes.add(FileOutcome(record.id, FileOutcome.Status.CANCELED)); continue }
            try {
                val payload = locatePayload(record) ?: error("Storage unavailable")
                val covered = plan.descendants.filter { it.id == payload.id || it.relativePath.startsWith(payload.relative.trimEnd('/') + "/") }
                val error = validateGroup(covered, plan.fingerprints, covered.filter { it.isDirectory }.associate { folder -> folder.id to covered.filter { it.parentId == folder.id }.map { it.id }.toSet() })
                require(error == null) { error ?: "Changed" }
                covered.sortedWith(compareBy<FileEntry> { it.isDirectory }.thenByDescending { it.relativePath.length }).forEach { item ->
                    val node = resolveInternal(item.id)
                    if (node.directory) require(children(node).isEmpty()) { "New trash child" }
                    deleteExact(node)
                }
                deleteMeta(record)
                outcomes.add(FileOutcome(record.id, FileOutcome.Status.SUCCESS))
            } catch (e: Exception) { outcomes.add(FileOutcome(record.id, FileOutcome.Status.FAILED, e.message)) }
        }
        val removed = outcomes.filter { it.status == FileOutcome.Status.SUCCESS }.map { it.id }.toSet()
        saveTrashCache(readTrashCache().filterNot { it.id in removed })
        recordOperation(operation, "emptyTrash", outcomes)
        finishJournal(operation)
        return OperationResult(operation, outcomes)
        } finally { mutationLock.unlock() }
    }

    override fun history(): List<OperationHistoryEntry> {
        val data = try { JSONArray(prefs.getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        val completed = (0 until data.length()).map { index ->
            val obj = data.getJSONObject(index)
            val raw = obj.optJSONArray("outcomes") ?: JSONArray()
            val outcomes = (0 until raw.length()).map { itemIndex ->
                val item = raw.getJSONObject(itemIndex)
                val recoveries = item.optJSONArray("recovery") ?: JSONArray()
                FileOutcome(item.optString("id"), FileOutcome.Status.valueOf(item.optString("status")),
                    item.optString("message").takeIf { it.isNotEmpty() && it != "null" },
                    item.optString("result").takeIf { it.isNotEmpty() && it != "null" },
                    (0 until recoveries.length()).map(recoveries::getString),
                    item.optString("fingerprint").takeIf { it.isNotEmpty() && it != "null" })
            }
            OperationHistoryEntry(obj.getString("id"), obj.getString("kind"), obj.optLong("at"), outcomes, obj.optBoolean("undone"))
        }
        val known = completed.map { it.id }.toSet()
        val pending = journal().filter { it.optString("id") !in known }.map { item ->
            OperationHistoryEntry(item.getString("id"), "interrupted ${item.optString("kind")}", item.optLong("at"),
                listOf(FileOutcome(item.optString("ids"), FileOutcome.Status.PARTIAL,
                    "Review source and partial destination ${item.optString("destination")} before retry")), false)
        }
        return pending + completed
    }

    override fun clearHistory() {
        mutationLock.withLock { prefs.edit().remove("history").apply() } // User files, trash sidecars, and unfinished journals stay intact.
    }

    override fun undoLast(): OperationResult {
        mutationLock.lock()
        try {
        val original = history().firstOrNull { !it.undone && it.kind in setOf("rename", "move", "trash") && it.outcomes.any { outcome -> outcome.status == FileOutcome.Status.SUCCESS } }
            ?: error("No supported operation to undo")
        val id = UUID.randomUUID().toString()
        val results = ArrayList<FileOutcome>()
        when (original.kind) {
            "trash" -> {
                val restoreIds = original.outcomes.filter { it.status == FileOutcome.Status.SUCCESS }.mapNotNull { it.resultingId }
                results.addAll(restore(restoreIds, emptyMap()).outcomes)
            }
            "rename" -> original.outcomes.filter { it.status == FileOutcome.Status.SUCCESS }.forEach { outcome ->
                try {
                    val current = requireNotNull(outcome.resultingId)
                    require(fingerprint(current) == outcome.resultFingerprint) { "Renamed item changed" }
                    val restored = rename(current, requireNotNull(outcome.message))
                    results.add(FileOutcome(outcome.id, FileOutcome.Status.SUCCESS, resultingId = restored.id))
                } catch (e: Exception) { results.add(FileOutcome(outcome.id, FileOutcome.Status.FAILED, e.message)) }
            }
            "move" -> original.outcomes.filter { it.status == FileOutcome.Status.SUCCESS }.forEach { outcome ->
                try {
                    val targetId = requireNotNull(outcome.resultingId)
                    require(fingerprint(targetId) == outcome.resultFingerprint) { "Moved destination changed" }
                    val restored = restore(outcome.recoveryIds, emptyMap())
                    require(restored.outcomes.all { it.status == FileOutcome.Status.SUCCESS }) { "Original could not be restored" }
                    val targetPlan = prepareDeletion(listOf(targetId))
                    require(targetPlan.complete) { "Destination could not be reviewed" }
                    val removed = moveToTrash(targetPlan)
                    require(removed.outcomes.all { it.status == FileOutcome.Status.SUCCESS }) { "Original restored; destination remains" }
                    results.add(FileOutcome(outcome.id, FileOutcome.Status.SUCCESS))
                } catch (e: Exception) { results.add(FileOutcome(outcome.id, FileOutcome.Status.PARTIAL, e.message)) }
            }
        }
        if (results.all { it.status == FileOutcome.Status.SUCCESS }) markUndone(original.id)
        recordOperation(id, "undo", results)
        return OperationResult(id, results)
        } finally { mutationLock.unlock() }
    }

    private fun matches(node: Node, filter: SearchFilter): Boolean {
        if (!node.name.contains(filter.nameQuery, ignoreCase = true)) return false
        if (filter.mimePrefix != null && node.mime?.startsWith(filter.mimePrefix) != true) return false
        val modified = node.modified
        if (filter.modifiedAfterMillis != null && (modified == null || modified < filter.modifiedAfterMillis)) return false
        if (filter.modifiedBeforeMillis != null && (modified == null || modified > filter.modifiedBeforeMillis)) return false
        val size = node.size
        if (filter.minSizeBytes != null && (size == null || size < filter.minSizeBytes)) return false
        if (filter.maxSizeBytes != null && (size == null || size > filter.maxSizeBytes)) return false
        return true
    }

    private fun roots(): List<Root> {
        val out = ArrayList<Root>()
        prefs.all.keys.filter { it.startsWith("tree.") }.forEach { prefKey ->
            val key = prefKey.removePrefix("tree.")
            val raw = prefs.getString(prefKey, null) ?: return@forEach
            val tree = Uri.parse(raw)
            val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == tree }
            out.add(Root(key, prefs.getString("label.$key", "Selected folder") ?: "Selected folder", tree, null, StorageLocation.Kind.SELECTED_FOLDER,
                grant?.isReadPermission == true && grant?.isWritePermission == true && prefs.getBoolean("write.$key", false)))
        }
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            val manager = context.getSystemService(StorageManager::class.java)
            manager.storageVolumes.forEach { volume ->
                val path = volume.directory ?: return@forEach
                if (!path.exists()) return@forEach
                val key = "f" + digest(path.canonicalPath.toByteArray()).take(24)
                val description = volume.getDescription(context) ?: path.name
                val kind = when {
                    !volume.isRemovable -> StorageLocation.Kind.PHONE
                    description.contains("usb", true) || path.path.contains("usb", true) -> StorageLocation.Kind.USB
                    else -> StorageLocation.Kind.SD_CARD
                }
                out.add(Root(key, description, null, path.canonicalFile, kind, path.canWrite()))
            }
        }
        return out.distinctBy { it.key }
    }

    private fun rootId(root: Root): String = if (root.tree != null) "S.${root.key}.${encode(DocumentsContract.getTreeDocumentId(root.tree))}" else "F.${root.key}"

    private fun resolve(id: String): Node = resolveInternal(id, false)
    private fun resolveInternal(id: String, allowTrash: Boolean = true): Node {
        val pieces = id.split('.')
        require(pieces.size >= 2 && (pieces[0] == "S" || pieces[0] == "F")) { "Invalid item ID" }
        val root = roots().firstOrNull { it.key == pieces[1] } ?: error("Location disconnected or grant revoked")
        require((pieces[0] == "S") == (root.tree != null)) { "Invalid location ID" }
        if (root.file != null) {
            var file = root.file.canonicalFile
            var relative = ""
            for (segment in pieces.drop(2)) {
                val name = decode(segment)
                if (allowTrash && name == trashName) { /* reserved module folder */ } else validName(name)
                if (!allowTrash) require(name != trashName) { "Trash is managed separately" }
                file = StorageSafety.confinedChild(root.file, file, name)
                relative = if (relative.isEmpty()) name else "$relative/$name"
            }
            require(file.exists()) { "Item missing" }
            val parent = if (pieces.size > 2) pieces.dropLast(1).joinToString(".") else null
            return Node(root, id, parent, file.name, relative, file.isDirectory, if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else mime(file.name), if (file.isFile) file.length() else null, file.lastModified().takeIf { it > 0 }, null, root.writable && file.canWrite(), file, null)
        }
        val tree = requireNotNull(root.tree)
        require(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) { "Selected-folder grant revoked; reconnect location" }
        val docIds = pieces.drop(2).map(::decode)
        require(docIds.isNotEmpty() && docIds.first() == DocumentsContract.getTreeDocumentId(tree)) { "Invalid tree root" }
        var parentUri: Uri? = null
        var parentId: String? = null
        var relative = ""
        var node: Node? = null
        for ((index, docId) in docIds.withIndex()) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val data = query(uri)
            if (index > 0) {
                val actual = childDocumentIds(tree, requireNotNull(parentUri))
                require(docId in actual) { "Document no longer belongs to granted folder" }
                if (!allowTrash) require(data.name != trashName) { "Trash is managed separately" }
                relative = if (relative.isEmpty()) data.name else "$relative/${data.name}"
            }
            val currentId = pieces.take(index + 3).joinToString(".")
            node = data.copy(root = root, id = currentId, parentId = parentId, relative = relative, uri = uri, writable = root.writable && data.writable)
            parentUri = uri
            parentId = currentId
        }
        return requireNotNull(node)
    }

    private fun query(uri: Uri): Node {
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            require(cursor.moveToFirst()) { "Document unavailable" }
            return nodeFromCursor(cursor)
        }
        error("Provider did not return document")
    }

    private fun nodeFromCursor(cursor: android.database.Cursor): Node {
        fun string(column: String): String? = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
        fun long(column: String): Long? = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
        val mime = string(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val flags = long(DocumentsContract.Document.COLUMN_FLAGS)?.toInt() ?: 0
        val canWrite = flags and (DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
        return Node(Root("", "", null, null, StorageLocation.Kind.SELECTED_FOLDER, false), "", null, string(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: "", "", mime == DocumentsContract.Document.MIME_TYPE_DIR, mime, long(DocumentsContract.Document.COLUMN_SIZE)?.takeIf { it >= 0 }, long(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.takeIf { it > 0 }, null, canWrite, null, null)
    }

    private fun childDocumentIds(tree: Uri, uri: Uri): Set<String> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(uri))
        val ids = HashSet<String>()
        resolver.query(childUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) ids.add(cursor.getString(col))
        } ?: error("Provider did not list children")
        return ids
    }

    private fun children(parent: Node): List<Node> {
        require(parent.directory)
        val file = parent.file
        if (file != null) return (file.listFiles() ?: error("Cannot list folder")).map { child -> resolveInternal(directChildId(parent.id, child.name)) }
        val tree = requireNotNull(parent.root.tree)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(requireNotNull(parent.uri)))
        val out = ArrayList<Node>()
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            val docCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) {
                val doc = cursor.getString(docCol)
                val data = nodeFromCursor(cursor)
                out.add(data.copy(root = parent.root, id = safChildId(parent.id, doc), parentId = parent.id,
                    relative = if (parent.relative.isEmpty()) data.name else "${parent.relative}/${data.name}",
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, doc), writable = parent.root.writable && data.writable))
            }
        } ?: error("Provider did not list children")
        return out
    }

    private fun create(parent: Node, name: String, mimeType: String, internal: Boolean = false): Node {
        if (internal && name == trashName) { /* reserved module folder */ } else validName(name)
        require(parent.directory && parent.writable) { "Folder is read only" }
        require(children(parent).none { it.name == name }) { "Name collision" }
        if (parent.file != null) {
            val child = File(parent.file, name)
            val okay = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) child.mkdir() else child.createNewFile()
            require(okay) { "Could not create item" }
            return resolveInternal(directChildId(parent.id, name))
        }
        val uri = DocumentsContract.createDocument(resolver, requireNotNull(parent.uri), mimeType, name) ?: error("Provider rejected creation")
        val created = resolveInternal(safChildId(parent.id, DocumentsContract.getDocumentId(uri)))
        require(created.name == name) { "Provider changed the requested name" }
        return created
    }

    private fun copyNode(source: Node, destination: Node, collision: CollisionChoice, cancel: Cancellation, progress: (Long) -> Unit, requestedName: String = source.name, replacementPlans: Map<String, DeletionPlan> = emptyMap(), visited: MutableSet<String> = HashSet()): Node? {
        require(destination.directory && destination.writable)
        validName(requestedName)
        val existing = children(destination).firstOrNull { it.name == requestedName }
        val finalName = when {
            existing == null -> requestedName
            collision == CollisionChoice.SKIP -> return null
            collision == CollisionChoice.KEEP_BOTH -> uniqueName(destination, requestedName)
            else -> requestedName
        }
        if (source.directory) {
            require(visited.add(nodeIdentity(source))) { "Cyclic or repeated source folder" }
            require(!source.relative.isEmpty())
            val target = if (existing != null && collision == CollisionChoice.REPLACE) {
                throw UnsupportedOperationException("Replacing folders requires a reviewed deletion plan")
            } else create(destination, finalName, DocumentsContract.Document.MIME_TYPE_DIR)
            for (child in children(source)) {
                if (cancel.isCanceled()) throw InterruptedException("Copy canceled; partial destination at ${target.relative}")
                require(copyNode(child, target, collision, cancel, progress, replacementPlans = replacementPlans, visited = visited) != null) { "Child skipped; partial destination at ${target.relative}" }
            }
            return target
        }
        // Stage under a unique name; no existing destination is touched before byte verification.
        val beforeSource = fingerprint(source)
        val partial = create(destination, ".${UUID.randomUUID()}.partial", source.mime ?: "application/octet-stream")
        var replacedIds: List<String> = emptyList()
        try {
            var sourceDigest = ""
            input(source).use { input -> output(partial).use { output ->
                sourceDigest = VerifiedCopy.transfer(input, output, cancel, progress)
            } }
            input(partial).use { VerifiedCopy.verify(it, sourceDigest) }
            require(fingerprint(source) == beforeSource) { "Source changed while copying" }
            val beforeCommit = children(destination).firstOrNull { it.name == finalName }
            if (beforeCommit != null) {
                if (collision != CollisionChoice.REPLACE || existing?.id != beforeCommit.id) error("Destination changed; partial copy retained")
                // Explicit REPLACE is still recoverable: move the old destination to trash first.
                val plan = replacementPlans[beforeCommit.id] ?: error("Replacement requires an explicitly reviewed deletion plan")
                require(plan.complete && plan.roots.size == 1 && plan.roots.single().id == beforeCommit.id) { "Replacement review does not match destination" }
                val result = moveToTrash(plan)
                replacedIds = result.outcomes.mapNotNull { it.resultingId }
                require(result.outcomes.all { it.status == FileOutcome.Status.SUCCESS }) { "Could not move old destination to trash" }
            }
            return renameInternal(partial, finalName)
        } catch (e: Exception) {
            // Keep partial for reconciliation; never report success or touch source.
            if (replacedIds.isNotEmpty()) throw PartialReplacement("${e.message}; replaced destination is recoverable in trash; partial copy ${partial.relative}", replacedIds, e)
            throw IllegalStateException("${e.message}; partial destination ${partial.relative}", e)
        }
    }

    private fun renameInternal(node: Node, newName: String): Node {
        val parent = resolveInternal(requireNotNull(node.parentId))
        if (node.file != null) {
            java.nio.file.Files.move(node.file.toPath(), File(requireNotNull(parent.file), newName).toPath())
            return resolveInternal(directChildId(parent.id, newName))
        }
        val uri = DocumentsContract.renameDocument(resolver, requireNotNull(node.uri), newName) ?: error("Provider could not finalize copy")
        return resolveInternal(safChildId(parent.id, DocumentsContract.getDocumentId(uri)))
    }

    private fun validateGroup(group: List<FileEntry>, fingerprints: Map<String, String>, dirChildren: Map<String, Set<String>>): String? {
        return try {
            val actualChildren = LinkedHashMap<String, Set<String>>()
            for (item in group) {
                val node = resolveInternal(item.id)
                if (fingerprint(node) != fingerprints[item.id]) return "File or folder changed; review again"
                if (node.directory) actualChildren[node.id] = children(node).map { it.id }.toSet()
            }
            if (!StorageSafety.reviewedChildrenUnchanged(dirChildren.filterKeys { it in actualChildren.keys }, actualChildren)) return "Folder children changed; review again"
            null
        } catch (e: Exception) { e.message ?: "Location unavailable" }
    }

    private fun remainingChildrenChanged(group: List<FileEntry>, currentId: String, expected: Map<String, Set<String>>, removed: Set<String>): String? {
        return try {
            for (folder in group.filter { it.isDirectory && it.id !in removed && (currentId == it.id || currentId.startsWith(it.id + ".")) }) {
                val actual = children(resolve(folder.id)).map { it.id }.toSet()
                if (actual != expected[folder.id].orEmpty() - removed) return "Folder children changed; review again"
            }
            null
        } catch (e: Exception) { e.message ?: "Folder unavailable" }
    }

    private fun fingerprint(node: Node): String {
        val identity = node.file?.let { file -> runCatching { val stat = Os.stat(file.path); "${stat.st_dev}:${stat.st_ino}" }.getOrNull() }
        val metadata = "${node.id}|${node.name}|${node.directory}|${node.size}|${node.modified}|${node.mime}|$identity"
        return digest(metadata.toByteArray() + if (node.directory) byteArrayOf() else digest(input(node)).toByteArray())
    }

    private fun trashOne(node: Node, cancel: Cancellation, progress: (Long) -> Unit): TrashEntry {
        val folder = ensureTrash(node.root)
        val uuid = UUID.randomUUID().toString()
        val payloadName = "$uuid.payload"
        val expectedFingerprint = if (node.file == null) fingerprint(node) else null
        val record = trashJson(uuid, node, payloadName)
        writeMeta(folder, uuid, record) // Recovery identity is on the same storage before any source removal.
        var payload: Node? = null
        var sourceDeleted = false
        try {
            payload = if (node.file != null) {
                val target = File(requireNotNull(folder.file), payloadName)
                require(node.file.renameTo(target)) { "Could not move to trash" }
                sourceDeleted = true
                resolveInternal(directChildId(folder.id, payloadName))
            } else {
                val copy = copyNode(node, folder, CollisionChoice.SKIP, cancel, progress, payloadName) ?: error("Trash collision")
                require(fingerprint(node) == expectedFingerprint) { "Source changed during trash copy" }
                copy
            }
            if (node.file == null) {
                // The copied payload has been verified, metadata is durable, and source still exists.
                require(fingerprint(node) == expectedFingerprint) { "Source changed before trash removal" }
                deleteExact(node)
                sourceDeleted = true
            }
            val entry = recordToTrash(record, node.root.key, true)
            saveTrashCache(readTrashCache() + entry)
            return entry
        } catch (e: Exception) {
            var rolledBack = !sourceDeleted
            if (node.file != null) {
                if (sourceDeleted) rolledBack = try { requireNotNull(payload?.file).renameTo(node.file) } catch (_: Exception) { false }
            } else if (!sourceDeleted) {
                try { val meta = children(folder).firstOrNull { it.name == "$uuid.meta.json" }; if (meta != null) deleteExact(meta) } catch (_: Exception) { }
                try { payload?.let(::deleteExact) } catch (_: Exception) { }
            }
            if (rolledBack) try { val meta = children(folder).firstOrNull { it.name == "$uuid.meta.json" }; if (meta != null) deleteExact(meta) } catch (_: Exception) { }
            throw e
        }
    }

    private fun trashEmptyFolder(node: Node): TrashEntry {
        require(node.directory && children(node).isEmpty())
        require(node.root.tree == null) { "Provider cannot safely remove a folder" }
        val folder = ensureTrash(node.root)
        val uuid = UUID.randomUUID().toString()
        val payloadName = "$uuid.folder"
        val record = trashJson(uuid, node, payloadName)
        writeMeta(folder, uuid, record)
        var payload: Node? = null
        var sourceDeleted = false
        try {
            payload = if (node.file != null) {
                require(node.file.renameTo(File(requireNotNull(folder.file), payloadName))) { "Could not move empty folder" }
                sourceDeleted = true
                resolveInternal(directChildId(folder.id, payloadName))
            } else create(folder, payloadName, DocumentsContract.Document.MIME_TYPE_DIR)
            if (node.file == null) { deleteExact(node); sourceDeleted = true }
            val entry = recordToTrash(record, node.root.key, true)
            saveTrashCache(readTrashCache() + entry)
            return entry
        } catch (e: Exception) {
            var rolledBack = !sourceDeleted
            if (node.file != null && sourceDeleted) rolledBack = try { requireNotNull(payload?.file).renameTo(node.file) } catch (_: Exception) { false }
            if (rolledBack) try { val meta = children(folder).firstOrNull { it.name == "$uuid.meta.json" }; if (meta != null) deleteExact(meta) } catch (_: Exception) { }
            if (!sourceDeleted && node.file == null) try { payload?.let(::deleteExact) } catch (_: Exception) { }
            throw e
        }
    }

    private fun trashJson(uuid: String, node: Node, payloadName: String) = JSONObject().put("id", uuid).put("payload", payloadName)
        .put("location", node.root.key).put("originalId", node.id).put("parentId", node.parentId)
        .put("name", node.name).put("path", node.relative).put("deleted", System.currentTimeMillis())
        .put("size", node.size).put("modified", node.modified).put("created", node.created).put("directory", node.directory)

    private fun recordToTrash(obj: JSONObject, location: String, available: Boolean) = TrashEntry(
        obj.getString("id"), location, obj.optString("originalId"), obj.optString("parentId"), obj.optString("name"), obj.optString("path"),
        obj.optLong("deleted"), obj.optLong("size").takeIf { !obj.isNull("size") }, obj.optLong("modified").takeIf { !obj.isNull("modified") },
        obj.optLong("created").takeIf { !obj.isNull("created") }, obj.optBoolean("directory"), available)

    private fun findTrash(root: Root): Node? {
        val parent = resolveInternal(rootId(root))
        return children(parent).firstOrNull { it.name == trashName && it.directory }
    }
    private fun ensureTrash(root: Root): Node = findTrash(root) ?: create(resolveInternal(rootId(root)), trashName, DocumentsContract.Document.MIME_TYPE_DIR, internal = true)
    private fun locatePayload(record: TrashEntry): Node? {
        val root = roots().firstOrNull { it.key == record.locationId } ?: return null
        val folder = findTrash(root) ?: return null
        return children(folder).firstOrNull { it.name == "${record.id}.payload" || it.name == "${record.id}.folder" }
    }
    private fun writeMeta(folder: Node, uuid: String, json: JSONObject) {
        val meta = create(folder, "$uuid.meta.json", "application/json")
        output(meta).use {
            it.write(json.toString().toByteArray(Charsets.UTF_8))
            it.flush()
            if (it is FileOutputStream) it.fd.sync()
        }
    }
    private fun readJson(node: Node): JSONObject = input(node).use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    private fun deleteMeta(record: TrashEntry) {
        val root = roots().firstOrNull { it.key == record.locationId } ?: error("Storage unavailable")
        val folder = findTrash(root) ?: error("Trash unavailable")
        val meta = children(folder).firstOrNull { it.name == "${record.id}.meta.json" } ?: error("Recovery metadata missing")
        deleteExact(meta)
    }
    private fun removeTrashPayloadAndMeta(record: TrashEntry) {
        locatePayload(record)?.let(::deleteExact)
        deleteMeta(record)
        saveTrashCache(readTrashCache().filterNot { it.id == record.id })
    }

    private fun ensureOriginalParent(record: TrashEntry): Node {
        val root = roots().firstOrNull { it.key == record.locationId } ?: error("Storage disconnected")
        var current = resolve(rootId(root))
        val segments = record.originalPath.split('/').dropLast(1).filter { it.isNotEmpty() }
        for (segment in segments) {
            validName(segment)
            current = children(current).firstOrNull { it.name == segment && it.directory } ?: create(current, segment, DocumentsContract.Document.MIME_TYPE_DIR)
        }
        return current
    }

    private fun deleteExact(node: Node) {
        require(node.parentId != null) { "Cannot remove storage root" }
        if (node.directory) {
            require(node.root.tree == null) { "Provider folder deletion is unsafe after review" }
            require(children(node).isEmpty()) { "Folder is not empty" }
        }
        if (node.file != null) require(node.file.delete()) { "Delete failed" }
        else require(DocumentsContract.deleteDocument(resolver, requireNotNull(node.uri))) { "Provider rejected deletion" }
    }

    private fun input(node: Node): InputStream = if (node.file != null) FileInputStream(node.file) else resolver.openInputStream(requireNotNull(node.uri)) ?: error("Read unsupported")
    private fun output(node: Node): OutputStream = if (node.file != null) FileOutputStream(node.file) else resolver.openOutputStream(requireNotNull(node.uri), "wt") ?: error("Write unsupported")
    private fun uniqueName(parent: Node, name: String): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        val names = children(parent).map { it.name }.toHashSet()
        for (number in 1..10000) { val choice = "$stem ($number)$extension"; if (choice !in names) return choice }
        error("No free filename")
    }
    private fun validName(name: String) {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name && name != trashName) { "Invalid file name" }
    }
    private fun safChildId(parentId: String, docId: String) = "$parentId.${encode(docId)}"
    private fun directChildId(parentId: String, name: String) = "$parentId.${encode(name)}"
    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String): String = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    private fun digest(input: InputStream): String = input.use { stream ->
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) { val count = stream.read(buffer); if (count < 0) break; md.update(buffer, 0, count) }
        md.digest().toHex()
    }
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun mime(name: String): String? = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
    private fun nodeIdentity(node: Node): String = node.root.key + ":" + (node.file?.canonicalPath ?: DocumentsContract.getDocumentId(requireNotNull(node.uri)))
    private fun safeAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    private fun readTrashCache(): List<TrashEntry> = try {
        val data = JSONArray(prefs.getString("trash.cache", "[]"))
        (0 until data.length()).map { index -> val obj = data.getJSONObject(index); recordToTrash(obj, obj.getString("location"), obj.optBoolean("available")) }
    } catch (_: Exception) { emptyList() }
    private fun saveTrashCache(entries: List<TrashEntry>) {
        val array = JSONArray()
        entries.distinctBy { it.id }.forEach { entry ->
            array.put(JSONObject().put("id", entry.id).put("location", entry.locationId).put("originalId", entry.originalId)
                .put("parentId", entry.originalParentId).put("name", entry.originalName).put("path", entry.originalPath)
                .put("deleted", entry.trashedAtMillis).put("size", entry.sizeBytes).put("modified", entry.modifiedAtMillis)
                .put("created", entry.createdAtMillis).put("directory", entry.isDirectory).put("available", entry.available))
        }
        prefs.edit().putString("trash.cache", array.toString()).apply()
    }
    private fun recordOperation(id: String, kind: String, outcomes: List<FileOutcome>) {
        val old = try { JSONArray(prefs.getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        val entry = JSONObject().put("id", id).put("kind", kind).put("at", System.currentTimeMillis()).put("outcomes", JSONArray().also { array ->
            outcomes.forEach { outcome -> array.put(JSONObject().put("id", outcome.id).put("status", outcome.status.name)
                .put("message", outcome.message).put("result", outcome.resultingId)
                .put("recovery", JSONArray(outcome.recoveryIds)).put("fingerprint", outcome.resultFingerprint)) }
        })
        val updated = JSONArray().put(entry)
        for (index in 0 until minOf(old.length(), 99)) updated.put(old.get(index))
        prefs.edit().putString("history", updated.toString()).apply()
    }
    private fun markUndone(id: String) {
        val data = JSONArray(prefs.getString("history", "[]"))
        for (index in 0 until data.length()) {
            val item = data.getJSONObject(index)
            if (item.optString("id") == id) item.put("undone", true)
        }
        prefs.edit().putString("history", data.toString()).apply()
    }
    private fun journal(): List<JSONObject> = try {
        val data = JSONArray(prefs.getString("journal", "[]"))
        (0 until data.length()).map(data::getJSONObject)
    } catch (_: Exception) { emptyList() }
    private fun startJournal(id: String, kind: String, ids: List<String>, destination: String?) {
        val updated = JSONArray()
        journal().forEach { updated.put(it) }
        updated.put(JSONObject().put("id", id).put("kind", kind).put("ids", JSONArray(ids))
            .put("destination", destination).put("at", System.currentTimeMillis()))
        require(prefs.edit().putString("journal", updated.toString()).commit()) { "Could not persist operation journal" }
    }
    private fun finishJournal(id: String) {
        val updated = JSONArray()
        journal().filterNot { it.optString("id") == id }.forEach { updated.put(it) }
        prefs.edit().putString("journal", updated.toString()).commit()
    }
}
