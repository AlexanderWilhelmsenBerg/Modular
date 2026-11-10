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
import java.util.zip.ZipFile

/** Performs blocking I/O. Call only from an I/O dispatcher after the user's explicit Scan action. */
class CleanerRepositoryAdapter(private val repository: FilesRepository, private val context: Context? = null) {
    data class Snapshot(val entries: List<CleanerEngine.Entry>, val errors: List<String>)
    data class Execution(val trash: OperationResult?, val renames: List<FileOutcome>, val errors: List<String>)

    fun snapshot(rootId: String, cancel: Cancellation = Cancellation { false },
                 excludedRules: Set<String> = emptySet()): Snapshot {
        val entries = mutableListOf<CleanerEngine.Entry>()
        val errors = mutableListOf<String>()
        val root = repository.get(rootId)
        if (!root.isDirectory) throw IllegalArgumentException("Cleaner scope must be a directory")
        val pending = ArrayDeque<FileEntry>()
        pending.add(root)
        val seen = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            if (cancel.isCanceled()) throw IOException("Cleaner scan canceled")
            val folder = pending.removeFirst()
            if (!seen.add(folder.id)) continue
            val children = mutableListOf<FileEntry>()
            var offset = 0
            var complete = true
            try {
                while (true) {
                    if (cancel.isCanceled()) throw IOException("Cleaner scan canceled")
                    val page = repository.list(folder.id, offset, 100, showHidden = true)
                    children.addAll(page.entries)
                    if (!page.complete) complete = false
                    val next = page.nextOffset ?: break
                    if (next <= offset) {
                        complete = false
                        errors.add("Listing did not advance: ${folder.relativePath}")
                        break
                    }
                    offset = next
                }
            } catch (ex: Exception) {
                complete = false
                errors.add("Could not fully list ${folder.relativePath}: ${ex.message ?: ex.javaClass.simpleName}")
            }
            val eligibleChildren = children.filterNot { excluded(it, excludedRules) }
            if (eligibleChildren.size != children.size) complete = false
            entries.add(toCleaner(folder, complete))
            for (child in eligibleChildren) {
                if (child.isDirectory) pending.add(child)
                else entries.add(toCleaner(child, true))
            }
        }
        return Snapshot(entries, errors)
    }

    fun analyze(snapshot: Snapshot, options: CleanerEngine.Options): CleanerEngine.Analysis {
        val analysis = CleanerEngine.analyze(snapshot.entries, options) { left, right -> byteEqual(left.id(), right.id()) }
        return CleanerEngine.Analysis(
            analysis.entries(), analysis.groups(), analysis.suggestions(),
            analysis.coverageWarnings() + snapshot.errors, analysis.options()
        )
    }

    /** Call only for the exact plan shown in the shared review after its Delete action is accepted. */
    fun executeReviewed(plan: CleanerEngine.Plan, cancel: Cancellation = Cancellation { false }): Execution {
        if (!plan.readyForReview()) return Execution(null, emptyList(), listOf("Cleaner plan is unresolved"))
        val roots = plan.trashSteps().map { it.entryId() }
        val current = snapshot(plan.scopeId(), cancel, plan.excludedRules())
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

    private fun listAllChildren(parentId: String): List<FileEntry> {
        val result = mutableListOf<FileEntry>()
        var offset = 0
        while (true) {
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

    private fun toCleaner(entry: FileEntry, childrenComplete: Boolean): CleanerEngine.Entry =
        CleanerEngine.Entry(
            entry.id, entry.locationId, entry.parentId, entry.relativePath, entry.name,
            if (entry.isDirectory) CleanerEngine.Kind.DIRECTORY else CleanerEngine.Kind.FILE,
            entry.sizeBytes, entry.modifiedAtMillis?.let(Instant::ofEpochMilli),
            entry.createdAtMillis?.let(Instant::ofEpochMilli), repository.fingerprint(entry.id),
            childrenComplete, apkEvidence(entry)
        )

    private fun apkEvidence(entry: FileEntry): CleanerEngine.ApkEvidence? {
        val appContext = context ?: return null
        if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true)) return null
        val temp = File.createTempFile("cleaner-apk-", ".apk", appContext.cacheDir)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(repository.openRead(entry.id)).use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output, 32 * 1024) }
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
            return null
        } finally {
            temp.delete()
        }
    }

    private fun byteEqual(leftId: String, rightId: String): Boolean {
        try {
            ParcelFileDescriptor.AutoCloseInputStream(repository.openRead(leftId)).use { left ->
                ParcelFileDescriptor.AutoCloseInputStream(repository.openRead(rightId)).use { right ->
                    val a = ByteArray(32 * 1024)
                    val b = ByteArray(a.size)
                    while (true) {
                        val countA = fillChunk(left, a)
                        val countB = fillChunk(right, b)
                        if (countA != countB) return false
                        if (countA == 0) return true
                        for (i in 0 until countA) if (a[i] != b[i]) return false
                    }
                }
            }
        } catch (ex: Exception) {
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
