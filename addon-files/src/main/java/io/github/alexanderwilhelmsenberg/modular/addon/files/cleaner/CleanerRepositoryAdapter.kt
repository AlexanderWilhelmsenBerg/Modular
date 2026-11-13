package io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.Cancellation
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FileEntry
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FileOutcome
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FilesRepository
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.OperationResult
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

/** Performs blocking I/O. Call only from an I/O dispatcher after the user's explicit Scan action. */
class CleanerRepositoryAdapter(private val repository: FilesRepository, private val context: Context? = null) {
    data class Snapshot(val entries: List<CleanerEngine.Entry>, val errors: List<String>)
    data class Execution(val trash: OperationResult?, val renames: List<FileOutcome>, val errors: List<String>)

    @JvmOverloads
    fun snapshot(rootId: String, cancel: Cancellation = Cancellation { false },
                 excludedRules: Set<String> = emptySet(),
                 progressiveOptions: CleanerEngine.Options? = null,
                 onProgress: ((CleanerEngine.Progress) -> Unit)? = null,
                 verifiedIds: Set<String>? = null): Snapshot {
        val entries = mutableListOf<CleanerEngine.Entry>()
        val errors = mutableListOf<String>()
        val root = repository.get(rootId)
        if (!root.isDirectory) throw IllegalArgumentException("Cleaner scope must be a directory")
        val pending = ArrayDeque<FileEntry>()
        pending.add(root)
        val downloadFolders = mutableMapOf(root.id to isDownloadName(root.name))
        val rules = excludedRules + (progressiveOptions?.excludedIds() ?: emptySet()) +
            (progressiveOptions?.protectedIds() ?: emptySet())
        val verifyFingerprint = progressiveOptions == null
        val parseApk = progressiveOptions?.selectedCategories()?.contains(CleanerEngine.Category.OLDER_VERSION) == true
        val seen = mutableSetOf<String>()
        var foldersScanned = 0
        var filesScanned = 0
        while (pending.isNotEmpty()) {
            if (cancel.isCanceled()) throw IOException("Cleaner scan canceled")
            val folder = pending.removeFirst()
            if (!seen.add(folder.id)) continue
            val children = mutableListOf<CleanerEngine.Entry>()
            val directoryPreviews = mutableListOf<CleanerEngine.Entry>()
            var groupHintsDeferred = false
            var offset = 0
            var complete = true
            val inDownloads = downloadFolders.remove(folder.id) == true
            while (true) {
                if (cancel.isCanceled()) throw IOException("Cleaner scan canceled")
                val page = try {
                    repository.list(folder.id, offset, 100, showHidden = true)
                } catch (ex: Exception) {
                    if (cancel.isCanceled()) throw IOException("Cleaner scan canceled", ex)
                    complete = false
                    errors.add("Could not fully list ${folder.relativePath}: ${ex.message ?: ex.javaClass.simpleName}")
                    break
                }
                if (!page.complete) complete = false
                val eligible = page.entries.filterNot { excluded(it, rules) }
                if (eligible.size != page.entries.size) complete = false
                val previews = eligible.map(::metadataPreview)
                val filePreviews = previews.filter { it.kind() == CleanerEngine.Kind.FILE }
                filesScanned += filePreviews.size
                if (!groupHintsDeferred) {
                    val directories = previews.filter { it.kind() == CleanerEngine.Kind.DIRECTORY }
                    if (children.size + directoryPreviews.size + previews.size > 1_000) {
                        groupHintsDeferred = true
                        directoryPreviews.clear()
                    } else directoryPreviews.addAll(directories)
                }
                if (progressiveOptions != null && onProgress != null &&
                    (progressiveOptions.includeSubfolders() || folder.id == rootId)) {
                    val suggestions = CleanerEngine.progressiveSuggestions(filePreviews, inDownloads, progressiveOptions)
                    if (suggestions.isNotEmpty()) {
                        val mentioned = suggestions.map { it.entryId() }.toSet()
                        if (cancel.isCanceled()) throw IOException("Cleaner scan canceled")
                        onProgress(CleanerEngine.Progress(filePreviews.filter { it.id() in mentioned }.associateBy { it.id() },
                            emptyList(), suggestions, emptyList(), foldersScanned, filesScanned, true))
                    }
                }
                val converted = eligible.mapNotNull { child ->
                    if (cancel.isCanceled()) throw IOException("Cleaner scan canceled")
                    if (child.isDirectory) {
                        pending.add(child)
                        downloadFolders[child.id] = inDownloads || isDownloadName(child.name)
                        null
                    } else toCleaner(child, true, cancel, verifyFingerprint &&
                        (verifiedIds == null || child.id in verifiedIds),
                        parseApk && (progressiveOptions?.includeSubfolders() != false || folder.id == rootId))
                }
                children.addAll(converted)
                if (children.size > 1_000 && !groupHintsDeferred) {
                    groupHintsDeferred = true
                    directoryPreviews.clear()
                }
                val next = page.nextOffset ?: break
                if (next <= offset) {
                    complete = false
                    errors.add("Listing did not advance: ${folder.relativePath}")
                    break
                }
                offset = next
            }
            if (cancel.isCanceled()) throw IOException("Cleaner scan canceled")
            entries.add(toCleaner(folder, complete, cancel, verifyFingerprint &&
                (verifiedIds == null || folder.id in verifiedIds), false))
            entries.addAll(children)
            foldersScanned++
            if (progressiveOptions != null && onProgress != null &&
                (progressiveOptions.includeSubfolders() || folder.id == rootId)) {
                val groupChildren = if (groupHintsDeferred) emptyList() else children + directoryPreviews
                val groups = if (!groupHintsDeferred)
                    CleanerEngine.progressiveGroups(groupChildren, complete, progressiveOptions)
                    else emptyList()
                val memberIds = groups.flatMap { it.memberIds() }.toSet()
                val warnings = when {
                    !complete -> listOf("Incomplete folder enumeration: ${folder.relativePath}")
                    groupHintsDeferred -> listOf("Large folder: group hints deferred until scan finishes: ${folder.relativePath}")
                    else -> emptyList()
                }
                onProgress(CleanerEngine.Progress(groupChildren.filter { it.id() in memberIds }.associateBy { it.id() },
                    groups, emptyList(), warnings, foldersScanned, filesScanned, true))
            }
        }
        return Snapshot(entries, errors)
    }

    fun analyze(snapshot: Snapshot, options: CleanerEngine.Options,
                cancel: Cancellation = Cancellation { false }): CleanerEngine.Analysis {
        if (cancel.isCanceled()) throw CancellationException("Cleaner scan canceled")
        val analysis = CleanerEngine.analyze(snapshot.entries, options) { left, right -> byteEqual(left.id(), right.id(), cancel) }
        if (cancel.isCanceled()) throw CancellationException("Cleaner scan canceled")
        return CleanerEngine.Analysis(
            analysis.entries(), analysis.groups(), analysis.suggestions(),
            analysis.coverageWarnings() + snapshot.errors, analysis.options()
        )
    }

    /** Hydrates only the selected review manifest; preliminary revisions never become executable. */
    fun prepareReview(analysis: CleanerEngine.Analysis, keeperByGroupId: Map<String, String>,
                      selectedGroupIds: Set<String>, selectedSuggestionEntryIds: Set<String>,
                      cancel: Cancellation = Cancellation { false }): CleanerEngine.Plan {
        val preliminary = CleanerEngine.plan(analysis, keeperByGroupId, selectedGroupIds, selectedSuggestionEntryIds)
        if (preliminary.blockers().any { !it.startsWith("File fingerprint requires review preparation:") })
            return preliminary
        if (preliminary.manifest().isEmpty()) return preliminary
        val verifiedEntries = analysis.entries().toMutableMap()
        for ((directoryId, expectedChildren) in preliminary.directoryChildren()) {
            if (cancel.isCanceled()) throw CancellationException("Cleaner review canceled")
            val actualChildren = listAllChildren(directoryId, cancel).map { it.id }.toSet()
            if (actualChildren != expectedChildren) throw IOException("Folder contents changed since scan; scan again")
        }
        val parseApk = analysis.options().selectedCategories().contains(CleanerEngine.Category.OLDER_VERSION)
        for (id in preliminary.manifest().keys) {
            if (cancel.isCanceled()) throw CancellationException("Cleaner review canceled")
            val captured = analysis.entries()[id] ?: throw IOException("File missing since scan; scan again")
            val current = repository.get(id)
            if (!sameMetadata(captured, current)) throw IOException("File changed since scan; scan again")
            val verified = toCleaner(current, captured.childrenComplete(), cancel, true,
                parseApk && captured.apk() != null)
            if (!sameMetadata(captured, repository.get(id)))
                throw IOException("File changed during review preparation; scan again")
            if (captured.apk() != verified.apk())
                throw IOException("APK identity changed since scan; scan again")
            verifiedEntries[id] = verified
        }
        for (group in analysis.groups()) {
            if (group.id() !in selectedGroupIds || group.category() != CleanerEngine.Category.EXACT_DUPLICATE) continue
            val keeperId = keeperByGroupId[group.id()] ?: group.suggestedKeeperId()
                ?: throw IOException("Choose a copy to keep")
            for (id in group.memberIds()) if (id != keeperId && !byteEqual(keeperId, id, cancel))
                throw IOException("Duplicate content changed since scan; scan again")
        }
        val verified = CleanerEngine.Analysis(verifiedEntries, analysis.groups(), analysis.suggestions(),
            analysis.coverageWarnings(), analysis.options())
        return CleanerEngine.plan(verified, keeperByGroupId, selectedGroupIds, selectedSuggestionEntryIds)
    }

    private fun sameMetadata(captured: CleanerEngine.Entry, current: FileEntry): Boolean =
        captured.id() == current.id && captured.volumeId() == current.locationId &&
            captured.parentId() == current.parentId && captured.path() == current.relativePath &&
            captured.name() == current.name &&
            (captured.kind() == CleanerEngine.Kind.DIRECTORY) == current.isDirectory &&
            captured.size() == current.sizeBytes &&
            captured.modified()?.toEpochMilli() == current.modifiedAtMillis &&
            captured.created()?.toEpochMilli() == current.createdAtMillis

    /** Call only for the exact plan shown in the shared review after its Delete action is accepted. */
    fun executeReviewed(plan: CleanerEngine.Plan, cancel: Cancellation = Cancellation { false }): Execution {
        if (!plan.readyForReview()) return Execution(null, emptyList(), listOf("Cleaner plan is unresolved"))
        val roots = plan.trashSteps().map { it.entryId() }
        val current = snapshot(plan.scopeId(), cancel, plan.excludedRules(),
            verifiedIds = plan.manifest().keys)
        val preflight = CleanerEngine.preflight(plan, current.entries)
        if (!preflight.safeToExecute()) return Execution(
            null, emptyList(), listOf("Files changed since review; refresh review")
        )
        val deletion = repository.prepareDeletion(roots, cancel)
        if (!deletion.complete || deletion.errors.isNotEmpty())
            return Execution(null, emptyList(), deletion.errors.ifEmpty { listOf("Incomplete deletion enumeration") })
        val reviewedRemovals = plan.trashSteps().flatMap { listOf(it.entryId()) + it.reviewedDescendantIds() }.toSet()
        val preparedRemovals = (deletion.roots + deletion.descendants).map { it.id }.toSet()
        if (reviewedRemovals != preparedRemovals)
            return Execution(null, emptyList(), listOf("Deletion descendants changed since review"))
        val trash = try {
            repository.moveToTrash(deletion, cancel)
        } catch (ex: Exception) {
            return Execution(null, emptyList(), listOf(ex.message ?: "Move to trash failed"))
        }
        val outcomes = trash.outcomes.associateBy { it.id }
        val renames = mutableListOf<FileOutcome>()
        for (rename in plan.renameSteps()) {
            val needed = rename.requiredTrashIds()
            if (needed.any { outcomes[it]?.status != FileOutcome.Status.SUCCESS }) {
                renames.add(FileOutcome(rename.keeperId(), FileOutcome.Status.SKIPPED, "Removal incomplete; keeper retained under existing name"))
                continue
            }
            try {
                val keeper = repository.get(rename.keeperId())
                val reviewedKeeper = plan.manifest()[keeper.id]
                if (reviewedKeeper == null || repository.fingerprint(keeper.id) != reviewedKeeper.revision()) {
                    renames.add(FileOutcome(keeper.id, FileOutcome.Status.CHANGED, "Keeper changed; rename needs fresh review"))
                    continue
                }
                val parent = keeper.parentId
                if (parent == null || listAllChildren(parent).any { it.name == rename.newName() && it.id != keeper.id }) {
                    renames.add(FileOutcome(keeper.id, FileOutcome.Status.CHANGED, "Rename destination occupied; keeper retained"))
                    continue
                }
                val renamed = repository.rename(keeper.id, rename.newName())
                renames.add(FileOutcome(keeper.id, FileOutcome.Status.SUCCESS, resultingId = renamed.id))
            } catch (ex: Exception) {
                renames.add(FileOutcome(rename.keeperId(), FileOutcome.Status.FAILED, ex.message ?: "Rename failed; keeper retained"))
            }
        }
        return Execution(trash, renames, emptyList())
    }

    private fun listAllChildren(parentId: String, cancel: Cancellation = Cancellation { false }): List<FileEntry> {
        val result = mutableListOf<FileEntry>()
        var offset = 0
        while (true) {
            if (cancel.isCanceled()) throw CancellationException("Cleaner review canceled")
            val page = repository.list(parentId, offset, 100, showHidden = true)
            if (!page.complete) throw IOException("Incomplete collision check")
            result.addAll(page.entries)
            val next = page.nextOffset ?: return result
            if (next <= offset) throw IOException("Incomplete collision check")
            offset = next
        }
    }

    private fun excluded(entry: FileEntry, rules: Set<String>): Boolean {
        if (entry.id in rules) return true
        val path = if (entry.relativePath.startsWith('/')) entry.relativePath else "/${entry.relativePath}"
        return rules.any { rule -> rule.startsWith('/') && (path == rule || path.startsWith(rule.trimEnd('/') + "/")) }
    }

    private fun isDownloadName(name: String) = name.equals("download", true) || name.equals("downloads", true)

    /** Progress metadata is intentionally unverified; only snapshot entries carry repository fingerprints. */
    private fun metadataPreview(entry: FileEntry): CleanerEngine.Entry = CleanerEngine.Entry(
        entry.id, entry.locationId, entry.parentId, entry.relativePath, entry.name,
        if (entry.isDirectory) CleanerEngine.Kind.DIRECTORY else CleanerEngine.Kind.FILE,
        entry.sizeBytes, entry.modifiedAtMillis?.let(Instant::ofEpochMilli),
        entry.createdAtMillis?.let(Instant::ofEpochMilli), CleanerEngine.PENDING_REVISION, !entry.isDirectory, null
    )

    private fun toCleaner(entry: FileEntry, childrenComplete: Boolean, cancel: Cancellation,
                          verifyFingerprint: Boolean, parseApk: Boolean): CleanerEngine.Entry =
        CleanerEngine.Entry(
            entry.id, entry.locationId, entry.parentId, entry.relativePath, entry.name,
            if (entry.isDirectory) CleanerEngine.Kind.DIRECTORY else CleanerEngine.Kind.FILE,
            entry.sizeBytes, entry.modifiedAtMillis?.let(Instant::ofEpochMilli),
            entry.createdAtMillis?.let(Instant::ofEpochMilli),
            if (verifyFingerprint) repository.fingerprint(entry.id).also {
                if (cancel.isCanceled()) throw CancellationException("Cleaner scan canceled")
            } else CleanerEngine.PENDING_REVISION,
            childrenComplete, if (parseApk) apkEvidence(entry, cancel) else null
        )

    private fun apkEvidence(entry: FileEntry, cancel: Cancellation): CleanerEngine.ApkEvidence? {
        val appContext = context ?: return null
        if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true)) return null
        val temp = File.createTempFile("cleaner-apk-", ".apk", appContext.cacheDir)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(repository.openRead(entry.id)).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        if (cancel.isCanceled()) throw CancellationException("Cleaner scan canceled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            val info = appContext.packageManager.getPackageArchiveInfo(temp.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return null
            val signatures = info.signingInfo?.apkContentsSigners ?: return null
            if (signatures.isEmpty()) return null
            val signer = signatures.map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }.sorted().joinToString("+")
            val architectures = ZipFile(temp).use { zip ->
                zip.entries().asSequence().mapNotNull { zipEntry ->
                    val path = zipEntry.name.split('/')
                    if (path.size >= 3 && path[0] == "lib") path[1] else null
                }.toSet().sorted().joinToString("+").ifEmpty { "no-native-libs" }
            }
            val lower = entry.name.lowercase()
            val channel = when {
                "nightly" in lower -> "nightly"
                "beta" in lower -> "beta"
                "stable" in lower -> "stable"
                else -> null
            }
            return CleanerEngine.ApkEvidence(
                info.packageName, signer, info.longVersionCode, info.versionName,
                architectures, channel, info.splitNames?.sorted()?.joinToString("+") ?: "base",
                (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0
            )
        } catch (_: Exception) {
            if (cancel.isCanceled()) throw IOException("Cleaner scan canceled")
            return null
        } finally {
            temp.delete()
        }
    }

    private fun byteEqual(leftId: String, rightId: String, cancel: Cancellation): Boolean {
        try {
            ParcelFileDescriptor.AutoCloseInputStream(repository.openRead(leftId)).use { left ->
                ParcelFileDescriptor.AutoCloseInputStream(repository.openRead(rightId)).use { right ->
                    val a = ByteArray(32 * 1024)
                    val b = ByteArray(a.size)
                    while (true) {
                        if (cancel.isCanceled()) throw CancellationException("Cleaner scan canceled")
                        val countA = fillChunk(left, a)
                        val countB = fillChunk(right, b)
                        if (countA != countB) return false
                        if (countA == 0) return true
                        for (i in 0 until countA) if (a[i] != b[i]) return false
                    }
                }
            }
        } catch (ex: Exception) {
            if (cancel.isCanceled()) throw CancellationException("Cleaner scan canceled")
            throw IOException("Content comparison failed", ex)
        }
    }

    private fun fillChunk(input: InputStream, bytes: ByteArray): Int {
        var count = 0
        while (count < bytes.size) {
            val read = input.read(bytes, count, bytes.size - count)
            if (read < 0) break
            if (read == 0) {
                val one = input.read()
                if (one < 0) break
                bytes[count++] = one.toByte()
            } else count += read
        }
        return count
    }
}
