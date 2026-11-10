package io.github.alexanderwilhelmsenberg.modular.addon.files.documents

import android.content.Context
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FilesRepository
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.StorageLocation
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class MarkdownEncoding { UTF8, UTF8_BOM, UTF16_LE, UTF16_BE }
enum class MarkdownLineEnding { LF, CRLF, CR }

data class MarkdownDraft(val text: String, val baseFingerprint: String, val savedAtMillis: Long)
data class MarkdownSnapshot(
    val fileId: String,
    val text: String,
    val fingerprint: String,
    val encoding: MarkdownEncoding,
    val lineEnding: MarkdownLineEnding,
    val draft: MarkdownDraft?,
    val writable: Boolean,
    /** In-place writes on some document providers are not atomic. A private draft is kept until verified. */
    val providerSaveMayBeNonAtomic: Boolean = true,
)

sealed interface MarkdownSaveResult {
    data class Saved(val snapshot: MarkdownSnapshot) : MarkdownSaveResult
    data class Conflict(val currentFingerprint: String, val draft: MarkdownDraft) : MarkdownSaveResult
    data class Failed(val reason: String, val draft: MarkdownDraft) : MarkdownSaveResult
}

/** Blocking I/O. Call off the main thread; a document is limited to 4 MiB of encoded text. */
class MarkdownDocumentService(private val context: Context, private val repository: FilesRepository) {
    private val draftDir get() = File(context.filesDir, "files-markdown-drafts").apply { mkdirs() }

    fun open(fileId: String): MarkdownSnapshot {
        val entry = repository.get(fileId)
        require(!entry.isDirectory) { "A directory is not a Markdown document" }
        val bytes = repository.openRead(fileId).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { readLimited(it, MAX_BYTES) }
        }
        val (text, encoding) = decode(bytes)
        val lineEnding = detectLineEnding(text)
        return MarkdownSnapshot(fileId, text, repository.fingerprint(fileId), encoding, lineEnding, readDraft(fileId), entry.writable)
    }

    fun storeDraft(snapshot: MarkdownSnapshot, editedText: String): MarkdownDraft {
        val draft = MarkdownDraft(editedText, snapshot.fingerprint, System.currentTimeMillis())
        writeDraft(snapshot.fileId, draft)
        return draft
    }

    fun discardDraft(fileId: String) { draftFile(fileId).delete() }

    fun save(snapshot: MarkdownSnapshot, editedText: String): MarkdownSaveResult {
        val draft = storeDraft(snapshot, editedText)
        if (!snapshot.writable) return MarkdownSaveResult.Failed("Document is read-only", draft)
        val current = runCatching { repository.fingerprint(snapshot.fileId) }
            .getOrElse { return MarkdownSaveResult.Failed(it.message ?: "Document unavailable", draft) }
        if (current != snapshot.fingerprint) return MarkdownSaveResult.Conflict(current, draft)
        val bytes = try { encode(editedText, snapshot.encoding, snapshot.lineEnding) }
            catch (e: Exception) { return MarkdownSaveResult.Failed(e.message ?: "Encoding failed", draft) }
        return try {
            val entry = repository.get(snapshot.fileId)
            val selectedFolder = repository.locations().firstOrNull { it.id == entry.locationId }?.kind == StorageLocation.Kind.SELECTED_FOLDER
            if (selectedFolder) {
                repository.openWrite(snapshot.fileId, snapshot.fingerprint).use { descriptor -> writeBytes(descriptor, bytes) }
            } else {
                val staged = repository.beginStagedWrite(snapshot.fileId, snapshot.fingerprint)
                try {
                    staged.descriptor.use { writeBytes(it, bytes) }
                    repository.commitStagedWrite(staged.token)
                } catch (error: Exception) { repository.cancelStagedWrite(staged.token); throw error }
            }
            val saved = open(snapshot.fileId)
            if (saved.text != normalizeLineEndings(editedText, snapshot.lineEnding)) {
                MarkdownSaveResult.Failed("Provider did not preserve the complete document", draft)
            } else {
                discardDraft(snapshot.fileId)
                MarkdownSaveResult.Saved(saved.copy(draft = null))
            }
        } catch (e: Exception) {
            MarkdownSaveResult.Failed(e.message ?: "Save failed; the private draft remains available", draft)
        }
    }

    private fun writeBytes(descriptor: android.os.ParcelFileDescriptor, bytes: ByteArray) {
        FileOutputStream(descriptor.fileDescriptor).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    /** Creates a new document. An existing name is never replaced. */
    fun saveAs(snapshot: MarkdownSnapshot, editedText: String, parentId: String, name: String): MarkdownSaveResult {
        val draft = storeDraft(snapshot, editedText)
        if (name.isBlank() || name.contains('/') || name.contains('\\'))
            return MarkdownSaveResult.Failed("Invalid document name", draft)
        var offset = 0
        var existing = false
        do {
            val page = repository.list(parentId, offset = offset, limit = 200, showHidden = true)
            if (page.entries.any { it.name == name }) { existing = true; break }
            offset = page.nextOffset ?: break
        } while (true)
        if (existing) return MarkdownSaveResult.Failed("A document with this name already exists", draft)
        return try {
            val created = repository.createFile(parentId, name, "text/markdown")
            if (created.name != name) return MarkdownSaveResult.Failed("Provider changed the new document name", draft)
            val copy = snapshot.copy(fileId = created.id, fingerprint = repository.fingerprint(created.id), writable = created.writable)
            when (val result = save(copy, editedText)) {
                is MarkdownSaveResult.Saved -> { discardDraft(snapshot.fileId); result }
                is MarkdownSaveResult.Conflict -> MarkdownSaveResult.Failed("New document changed during save", draft)
                is MarkdownSaveResult.Failed -> MarkdownSaveResult.Failed("New document may be incomplete: ${result.reason}", draft)
            }
        } catch (e: Exception) { MarkdownSaveResult.Failed(e.message ?: "Save as failed", draft) }
    }

    private fun draftFile(id: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(draftDir, "$hash.draft")
    }

    private fun writeDraft(id: String, draft: MarkdownDraft) {
        val target = draftFile(id)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        DataOutputStream(FileOutputStream(temporary)).use { out ->
            out.writeInt(DRAFT_VERSION)
            out.writeUTF(id)
            out.writeUTF(draft.baseFingerprint)
            out.writeLong(draft.savedAtMillis)
            val bytes = draft.text.toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= MAX_BYTES) { "Draft exceeds 4 MiB" }
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }
        check(temporary.renameTo(target)) { "Cannot preserve the private draft" }
    }

    private fun readDraft(id: String): MarkdownDraft? = runCatching {
        val file = draftFile(id)
        if (!file.isFile || file.length() > MAX_BYTES + 4096L) return@runCatching null
        DataInputStream(FileInputStream(file)).use { input ->
            if (input.readInt() != DRAFT_VERSION || input.readUTF() != id) return@use null
            val fingerprint = input.readUTF()
            val savedAt = input.readLong()
            val length = input.readInt()
            if (length < 0 || length > MAX_BYTES) return@use null
            val bytes = ByteArray(length)
            input.readFully(bytes)
            MarkdownDraft(String(bytes, StandardCharsets.UTF_8), fingerprint, savedAt)
        }
    }.getOrNull()

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        private const val DRAFT_VERSION = 1

        fun decode(bytes: ByteArray): Pair<String, MarkdownEncoding> {
            val (offset, charset, encoding) = when {
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Triple(3, StandardCharsets.UTF_8, MarkdownEncoding.UTF8_BOM)
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Triple(2, StandardCharsets.UTF_16LE, MarkdownEncoding.UTF16_LE)
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Triple(2, StandardCharsets.UTF_16BE, MarkdownEncoding.UTF16_BE)
                else -> Triple(0, StandardCharsets.UTF_8, MarkdownEncoding.UTF8)
            }
            try {
                val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
                require(!text.contains('\u0000')) { "Binary or unsupported text encoding" }
                return text to encoding
            } catch (e: CharacterCodingException) { throw IllegalArgumentException("Malformed or unsupported text encoding", e) }
        }

        fun detectLineEnding(text: String): MarkdownLineEnding = when {
            text.contains("\r\n") -> MarkdownLineEnding.CRLF
            text.contains('\r') -> MarkdownLineEnding.CR
            else -> MarkdownLineEnding.LF
        }

        fun normalizeLineEndings(text: String, ending: MarkdownLineEnding): String {
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            return when (ending) {
                MarkdownLineEnding.LF -> normalized
                MarkdownLineEnding.CRLF -> normalized.replace("\n", "\r\n")
                MarkdownLineEnding.CR -> normalized.replace("\n", "\r")
            }
        }

        fun encode(text: String, encoding: MarkdownEncoding, ending: MarkdownLineEnding): ByteArray {
            val normalized = normalizeLineEndings(text, ending)
            val charset = when (encoding) {
                MarkdownEncoding.UTF8, MarkdownEncoding.UTF8_BOM -> StandardCharsets.UTF_8
                MarkdownEncoding.UTF16_LE -> StandardCharsets.UTF_16LE
                MarkdownEncoding.UTF16_BE -> StandardCharsets.UTF_16BE
            }
            val prefix = when (encoding) {
                MarkdownEncoding.UTF8_BOM -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                MarkdownEncoding.UTF16_LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                MarkdownEncoding.UTF16_BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
                else -> byteArrayOf()
            }
            val body = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(normalized))
            require(body.remaining() + prefix.size <= MAX_BYTES) { "Document exceeds 4 MiB" }
            val output = ByteArray(prefix.size + body.remaining())
            prefix.copyInto(output)
            body.get(output, prefix.size, body.remaining())
            return output
        }

        private fun readLimited(input: FileInputStream, maxBytes: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                require(output.size() + count <= maxBytes) { "Document exceeds 4 MiB" }
                output.write(buffer, 0, count)
            }
        }
    }
}

/** A bounded editing history used by the native editor for Undo and Redo. */
class MarkdownUndoHistory(initialText: String, private val limit: Int = 100) {
    private val past = ArrayDeque<String>()
    private val future = ArrayDeque<String>()
    private var pastCharacters = 0
    var text: String = initialText
        private set
    fun edit(next: String) {
        if (next == text) return
        past.addLast(text)
        pastCharacters += text.length
        while (past.size > limit || pastCharacters > 8 * 1024 * 1024) pastCharacters -= past.removeFirst().length
        text = next
        future.clear()
    }
    fun undo(): String { if (past.isNotEmpty()) { future.addLast(text); text = past.removeLast(); pastCharacters -= text.length }; return text }
    fun redo(): String { if (future.isNotEmpty()) { past.addLast(text); pastCharacters += text.length; text = future.removeLast() }; return text }
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
}
