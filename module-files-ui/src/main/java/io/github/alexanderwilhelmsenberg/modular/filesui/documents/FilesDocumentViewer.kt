package io.github.alexanderwilhelmsenberg.modular.filesui.documents

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** The same native component is embedded by Core and by the Files app. Content flows over scoped PFDs. */
@Composable
fun FilesDocumentViewer(
    documentId: String,
    kind: String,
    baseFingerprint: String,
    documentLoader: suspend (String, String) -> ParcelFileDescriptor?,
    onCommit: suspend () -> Boolean,
    onCancel: suspend () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (kind) {
        "markdown" -> MarkdownViewer(documentId, baseFingerprint, documentLoader, onCommit, onCancel, onClose, modifier)
        "pdf" -> PdfViewer(documentId, baseFingerprint, documentLoader, onClose, modifier)
        else -> Text("Unsupported document")
    }
}

private data class MarkdownContent(val text: String, val encoding: String, val lineEnding: String, val hash: String)

@Composable
private fun MarkdownViewer(id: String, fingerprint: String, loader: suspend (String, String) -> ParcelFileDescriptor?, onCommit: suspend () -> Boolean, onCancel: suspend () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember(id) { mutableStateOf<MarkdownContent?>(null) }
    var text by remember(id) { mutableStateOf("") }
    var mode by remember(id) { mutableStateOf("preview") }
    var status by remember(id) { mutableStateOf("Loading document…") }
    var closePrompt by remember(id) { mutableStateOf(false) }
    var conflict by remember(id) { mutableStateOf(false) }
    val draftResolved = remember(id) { AtomicBoolean(false) }
    val undo = remember(id) { TextUndoHistory() }
    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch {
            val source = original ?: return@launch
            try {
                val bytes = encodeMarkdown(text, source.encoding, source.lineEnding)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
                        ?: throw IllegalStateException("Destination is not writable")
                    val verified = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Cannot verify saved copy")
                    require(sha256(verified) == sha256(bytes)) { "Saved copy failed verification" }
                    draftFile(context, id).delete()
                }
                draftResolved.set(true)
                status = "Saved a copy"
                onClose()
            } catch (error: Exception) { status = "Save as failed; draft retained: ${error.message ?: "unknown error"}" }
        }
    }
    LaunchedEffect(id) {
        try {
            val loaded = withContext(Dispatchers.IO) { loadMarkdown(loader, id) }
            val draft = withContext(Dispatchers.IO) { readDraft(context, id) }
            original = loaded
            text = if (draft?.first == loaded.hash) draft.second else loaded.text
            undo.reset(text)
            status = if (draft != null && draft.first != loaded.hash) "Original changed; a private draft is available. Review before saving." else ""
            if (draft != null && draft.first != loaded.hash) { text = draft.second; conflict = true; mode = "edit" }
        } catch (error: Exception) { status = error.message ?: "Cannot open document" }
    }
    val dirty = original?.text != text
    val latestText by rememberUpdatedState(text)
    val latestOriginal by rememberUpdatedState(original)
    val latestDirty by rememberUpdatedState(dirty)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(id, lifecycleOwner) {
        val flush = { if (latestDirty && !draftResolved.get()) latestOriginal?.let { source -> runCatching { saveDraft(context, id, source.hash, latestText) } }; Unit }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) flush() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); flush() }
    }
    BackHandler(enabled = true) { if (dirty) closePrompt = true else onClose() }
    LaunchedEffect(id, text, original) {
        if (dirty && original != null) {
            delay(500)
            if (!draftResolved.get()) withContext(Dispatchers.IO) { saveDraft(context, id, original!!.hash, text) }
        }
    }
    suspend fun save() {
        val source = original ?: return
        var writeStarted = false
        status = "Saving…"
        try {
            val current = withContext(Dispatchers.IO) { loadMarkdown(loader, id) }
            if (current.hash != source.hash) { conflict = true; status = "Original changed outside Files. Reload or save a copy."; return }
            val bytes = encodeMarkdown(text, source.encoding, source.lineEnding)
            writeStarted = true
            withContext(Dispatchers.IO) {
                val descriptor = loader(id, "rw:$fingerprint") ?: throw IllegalStateException("Write permission unavailable")
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { stream -> stream.write(bytes); stream.flush(); stream.fd.sync() }
            }
            check(onCommit()) { "Staged save was not committed" }
            val verified = withContext(Dispatchers.IO) { loadMarkdown(loader, id) }
            if (!verified.hash.equals(sha256(bytes))) throw IllegalStateException("Provider did not preserve the complete document")
            original = verified
            withContext(Dispatchers.IO) { draftFile(context, id).delete() }
            draftResolved.set(true)
            status = "Saved"
            conflict = false
            closePrompt = false
        } catch (error: Exception) {
            if (writeStarted) try { onCancel() } catch (_: Exception) { }
            status = "Save failed; draft retained: ${error.message ?: "unknown error"}"
        }
    }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { if (dirty) closePrompt = true else onClose() }) { Text("Back") }
            TextButton(onClick = { mode = "preview" }) { Text("Preview") }
            TextButton(onClick = { mode = "edit" }) { Text("Edit") }
            if (dirty) Text("Unsaved changes", color = MaterialTheme.colorScheme.error)
        }
        if (status.isNotBlank()) Text(status, color = if (conflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (mode == "edit") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { save() } }, enabled = dirty && !conflict && original != null,
                    modifier = Modifier.testTag("files.document.markdown.save")) { Text("Save") }
                OutlinedButton(onClick = { saveAsLauncher.launch("Markdown-copy.md") }, enabled = original != null) { Text("Save as") }
                TextButton(onClick = { val next = undo.undo(); if (next != null) { draftResolved.set(false); text = next } }, enabled = undo.canUndo) { Text("Undo") }
                TextButton(onClick = { val next = undo.redo(); if (next != null) { draftResolved.set(false); text = next } }, enabled = undo.canRedo) { Text("Redo") }
            }
            var query by remember(id) { mutableStateOf("") }
            OutlinedTextField(query, { query = it.take(200) }, label = { Text("Find text") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (query.isNotEmpty()) Text("${Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(text).count()} matches")
            OutlinedTextField(text, { next ->
                if (next.length <= MAX_TEXT_CHARS) { draftResolved.set(false); undo.edit(next); text = next }
            }, modifier = Modifier.fillMaxWidth().weight(1f).testTag("files.document.markdown.editor"), label = { Text("Markdown") })
        } else {
            MarkdownPreview(text, id, loader, Modifier.weight(1f))
        }
    }
    if (closePrompt) AlertDialog(onDismissRequest = { closePrompt = false }, title = { Text("Unsaved changes") },
        text = { Text("Save your changes, discard them, or keep editing. A private draft remains available if you leave Home.") },
        confirmButton = { TextButton(onClick = { scope.launch { save() } }) { Text("Save") } },
        dismissButton = { Row {
            TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { draftFile(context, id).delete() }; draftResolved.set(true); onClose() } }) { Text("Discard") }
            TextButton(onClick = { closePrompt = false }) { Text("Cancel") }
        } })
    if (conflict) AlertDialog(onDismissRequest = {}, title = { Text("Original changed") },
        text = { Text("The original was edited elsewhere. Your private draft is retained. Reload the original or save a copy.") },
        confirmButton = { TextButton(onClick = { scope.launch {
            val reloaded = runCatching { withContext(Dispatchers.IO) { loadMarkdown(loader, id) } }.getOrNull()
            if (reloaded != null) { withContext(Dispatchers.IO) { draftFile(context, id).delete() }; original = reloaded; text = reloaded.text; undo.reset(text); conflict = false; status = "Reloaded" }
        } }) { Text("Reload") } }, dismissButton = { TextButton(onClick = { saveAsLauncher.launch("Markdown-copy.md") }) { Text("Save as a copy") } })
}

@Composable
private fun MarkdownPreview(text: String, documentId: String, loader: suspend (String, String) -> ParcelFileDescriptor?, modifier: Modifier) {
    val context = LocalContext.current
    val rows = remember(text) {
        var inCode = false
        text.lineSequence().take(20_000).map { line ->
            val fence = line.trimStart().startsWith("```")
            if (fence) inCode = !inCode
            PreviewLine(line, inCode, fence)
        }.toList()
    }
    LazyColumn(modifier.padding(vertical = 8.dp)) {
        items(rows) { row ->
            val line = row.text
            when {
                row.fence -> Text(if (row.inCode) "Code" else "", style = MaterialTheme.typography.labelSmall)
                row.inCode -> Text(line, fontFamily = FontFamily.Monospace)
                line.startsWith("# ") -> Text(line.drop(2), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                line.startsWith("## ") -> Text(line.drop(3), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                line.startsWith("### ") -> Text(line.drop(4), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                line.startsWith("- [ ] ", true) -> Text("☐ ${line.drop(6)}")
                line.startsWith("- [x] ", true) -> Text("☑ ${line.drop(6)}")
                line.startsWith("- ") || line.startsWith("* ") -> Text("• ${line.drop(2)}")
                line.startsWith("> ") -> Text("│ ${line.drop(2)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                line.startsWith("|") -> Text(line.trim('|').split('|').joinToString("  │  ") { it.trim() }, fontFamily = FontFamily.Monospace)
                line.contains("![") -> {
                    val image = Regex("!\\[([^]]*)]\\(([^)]+)\\)").find(line)
                    if (image == null) Text(line) else LocalMarkdownImage(documentId, image.groupValues[2], image.groupValues[1], loader)
                }
                else -> {
                    val link = Regex("\\[([^]]+)]\\(([^)]+)\\)").find(line)
                    if (link == null) Text(line) else {
                        Text(line.substring(0, link.range.first))
                        val destination = link.groupValues[2]
                        val scheme = runCatching { Uri.parse(destination).scheme?.lowercase() }.getOrNull()
                        if (scheme in setOf("https", "http", "mailto")) TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination))) }
                        }) { Text(link.groupValues[1]) }
                        else Text("${link.groupValues[1]} ($destination)")
                        Text(line.substring(link.range.last + 1))
                    }
                }
            }
        }
    }
}

private data class PreviewLine(val text: String, val inCode: Boolean, val fence: Boolean)

@Composable
private fun LocalMarkdownImage(documentId: String, path: String, alt: String, loader: suspend (String, String) -> ParcelFileDescriptor?) {
    var bitmap by remember(documentId, path) { mutableStateOf<Bitmap?>(null) }
    var error by remember(documentId, path) { mutableStateOf<String?>(null) }
    LaunchedEffect(documentId, path) {
        val uri = Uri.parse(path)
        val localPath = Uri.decode(path.substringBefore('#').substringBefore('?'))
        if (uri.scheme != null || localPath.startsWith('/') || localPath.startsWith('\\') || localPath.split('/', '\\').any { it == ".." || it == "." }) {
            error = "Remote or unsafe image blocked"
            return@LaunchedEffect
        }
        try {
            val encoded = Base64.encodeToString(localPath.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val imageId = "image:$documentId:$encoded"
            require(imageId.length <= 512) { "Image path is too long" }
            val bytes = withContext(Dispatchers.IO) {
                val descriptor = loader(imageId, "r") ?: throw IllegalStateException("Image permission unavailable")
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; require(output.size() + count <= 8 * 1024 * 1024) { "Image exceeds preview limit" }; output.write(buffer, 0, count) }
                    output.toByteArray()
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image cannot be decoded" }
            val sample = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= 2048 && bounds.outHeight / it <= 2048 }
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IllegalStateException("Image cannot be decoded")
        } catch (failure: Exception) { error = failure.message ?: "Image unavailable" }
    }
    DisposableEffect(bitmap) { val current = bitmap; onDispose { current?.recycle() } }
    bitmap?.let { Image(it.asImageBitmap(), alt.ifBlank { "Local image" }, Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit) }
        ?: Text("${alt.ifBlank { "Local image" }}: ${error ?: "Loading…"}")
}

private suspend fun loadMarkdown(loader: suspend (String, String) -> ParcelFileDescriptor?, id: String): MarkdownContent {
    val descriptor = loader(id, "r") ?: throw IllegalStateException("Read permission unavailable")
    val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) { val count = stream.read(buffer); if (count < 0) break; if (output.size() + count > MAX_TEXT_BYTES) throw IllegalArgumentException("Document exceeds 4 MiB"); output.write(buffer, 0, count) }
        output.toByteArray()
    }
    val (offset, charset, encoding) = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Triple(3, StandardCharsets.UTF_8, "utf8bom")
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Triple(2, StandardCharsets.UTF_16LE, "utf16le")
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Triple(2, StandardCharsets.UTF_16BE, "utf16be")
        else -> Triple(0, StandardCharsets.UTF_8, "utf8")
    }
    val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
    require(!text.contains('\u0000')) { "Binary or unsupported encoding" }
    val ending = if (text.contains("\r\n")) "crlf" else if (text.contains('\r')) "cr" else "lf"
    return MarkdownContent(text, encoding, ending, sha256(bytes))
}

private fun encodeMarkdown(text: String, encoding: String, ending: String): ByteArray {
    val plain = text.replace("\r\n", "\n").replace('\r', '\n')
    val normalized = when (ending) { "crlf" -> plain.replace("\n", "\r\n"); "cr" -> plain.replace("\n", "\r"); else -> plain }
    val (charset, prefix) = when (encoding) {
        "utf8bom" -> StandardCharsets.UTF_8 to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        "utf16le" -> StandardCharsets.UTF_16LE to byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        "utf16be" -> StandardCharsets.UTF_16BE to byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        else -> StandardCharsets.UTF_8 to byteArrayOf()
    }
    return (prefix + normalized.toByteArray(charset)).also { require(it.size <= MAX_TEXT_BYTES) }
}

private class TextUndoHistory {
    private val past = ArrayDeque<String>()
    private val future = ArrayDeque<String>()
    private var pastChars = 0
    var value: String = ""
        private set
    fun reset(text: String) { value = text; past.clear(); future.clear(); pastChars = 0 }
    fun edit(text: String) {
        if (text == value) return
        past.addLast(value); pastChars += value.length
        while (past.size > 100 || pastChars > 8 * 1024 * 1024) pastChars -= past.removeFirst().length
        value = text; future.clear()
    }
    fun undo(): String? { if (past.isEmpty()) return null; future.addLast(value); value = past.removeLast(); pastChars -= value.length; return value }
    fun redo(): String? { if (future.isEmpty()) return null; past.addLast(value); pastChars += value.length; value = future.removeLast(); return value }
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
}

private fun draftFile(context: Context, id: String): File = File(File(context.filesDir, "files-native-drafts").apply { mkdirs() }, sha256(id.toByteArray()) + ".draft")
private fun saveDraft(context: Context, id: String, hash: String, text: String) {
    val target = draftFile(context, id)
    val temp = File(target.parentFile, target.name + ".tmp")
    DataOutputStream(FileOutputStream(temp)).use { output -> output.writeUTF(hash); val bytes = text.toByteArray(); output.writeInt(bytes.size); output.write(bytes) }
    check(temp.renameTo(target))
}
private fun readDraft(context: Context, id: String): Pair<String, String>? = runCatching {
    val file = draftFile(context, id)
    if (!file.isFile || file.length() > MAX_TEXT_BYTES + 4096L) return@runCatching null
    DataInputStream(FileInputStream(file)).use { input -> val hash = input.readUTF(); val size = input.readInt(); if (size < 0 || size > MAX_TEXT_BYTES) return@use null; val bytes = ByteArray(size); input.readFully(bytes); hash to String(bytes) }
}.getOrNull()
private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private const val MAX_TEXT_BYTES = 4 * 1024 * 1024
private const val MAX_TEXT_CHARS = 4 * 1024 * 1024

@Composable
private fun PdfViewer(id: String, fingerprint: String, loader: suspend (String, String) -> ParcelFileDescriptor?, onClose: () -> Unit, modifier: Modifier) {
    BackHandler(enabled = true, onBack = onClose)
    val context = LocalContext.current
    var password by remember(id) { mutableStateOf("") }
    var passwordAttempt by remember(id) { mutableStateOf(0) }
    var passwordPrompt by remember(id) { mutableStateOf(false) }
    var status by remember(id) { mutableStateOf("Loading PDF…") }
    var pageCount by remember(id) { mutableStateOf(0) }
    var page by remember(id) { mutableStateOf(0) }
    var zoom by remember(id) { mutableStateOf(1f) }
    var bitmap by remember(id) { mutableStateOf<Bitmap?>(null) }
    var pageText by remember(id) { mutableStateOf<String?>(null) }
    var query by remember(id) { mutableStateOf("") }
    var jump by remember(id) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val positionKey = remember(id, fingerprint) { "pdf-" + sha256("$id:$fingerprint".toByteArray()) }
    LaunchedEffect(id, fingerprint) {
        val saved = context.getSharedPreferences("files-pdf-position", Context.MODE_PRIVATE).getString(positionKey, null)?.split(',')
        page = saved?.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        zoom = saved?.getOrNull(1)?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0.25f..4f } ?: 1f
    }
    LaunchedEffect(id, page, zoom, passwordAttempt) {
        try {
            val result = withContext(Dispatchers.IO) {
                val descriptor = loader(id, "r") ?: throw IllegalStateException("Read permission unavailable")
                val renderer = try {
                    if (Build.VERSION.SDK_INT >= 35) PdfAdvanced.open(descriptor, password)
                    else PdfRenderer(descriptor)
                } catch (error: Exception) { descriptor.close(); throw error }
                renderer.use { pdf ->
                    require(pdf.pageCount > 0) { "PDF has no pages" }
                    val actualPage = page.coerceIn(0, pdf.pageCount - 1)
                    pdf.openPage(actualPage).use { current ->
                        val scale = minOf(zoom.toDouble(), kotlin.math.sqrt(4_000_000.0 / current.width / current.height))
                        val width = (current.width * scale).toInt().coerceAtLeast(1)
                        val height = (current.height * scale).toInt().coerceAtLeast(1)
                        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        image.eraseColor(android.graphics.Color.WHITE)
                        current.render(image, null, Matrix().apply { setScale(width.toFloat() / current.width, height.toFloat() / current.height) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val text = if (Build.VERSION.SDK_INT >= 35) PdfAdvanced.pageText(current) else null
                        Triple(pdf.pageCount, image, text)
                    }
                }
            }
            bitmap = result.second
            pageText = result.third
            pageCount = result.first
            status = if (Build.VERSION.SDK_INT < 35) "Text search and passwords require Android 15 or newer" else if (pageText.isNullOrBlank()) "This page has no selectable text" else ""
            passwordPrompt = false
            context.getSharedPreferences("files-pdf-position", Context.MODE_PRIVATE).edit().putString(positionKey, "$page,$zoom").apply()
        } catch (error: Exception) {
            status = "Cannot render PDF: ${error.message ?: "unsupported or damaged file"}. Open with another app if needed."
            val encrypted = error is SecurityException || error.message?.contains(Regex("password|encrypt", RegexOption.IGNORE_CASE)) == true
            passwordPrompt = Build.VERSION.SDK_INT >= 35 && encrypted
        }
    }
    DisposableEffect(bitmap) { val current = bitmap; onDispose { current?.recycle() } }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onClose) { Text("Back") }
            OutlinedButton(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = page > 0) { Text("Previous") }
            OutlinedButton(onClick = { page = (page + 1).coerceAtMost(pageCount - 1) }, enabled = page + 1 < pageCount,
                modifier = Modifier.testTag("files.document.pdf.next")) { Text("Next") }
        }
        Text("Page ${if (pageCount > 0) page + 1 else 0} of $pageCount · Zoom ${(zoom * 100).toInt()}%",
            modifier = Modifier.testTag("files.document.pdf.page"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(jump, { jump = it.filter(Char::isDigit).take(6) }, label = { Text("Page") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = { jump.toIntOrNull()?.let { page = (it - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0)) } }) { Text("Go") }
            TextButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(0.25f) }) { Text("−") }
            TextButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(4f) }) { Text("+") }
        }
        if (status.isNotBlank()) Text(status)
        if (Build.VERSION.SDK_INT >= 35) {
            OutlinedTextField(query, { query = it.take(200) }, label = { Text("Find on page") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (query.isNotBlank()) Text("${pageText?.split(query, ignoreCase = true)?.size?.minus(1) ?: 0} matches on this page")
            if (query.isNotBlank()) TextButton(onClick = { scope.launch {
                val found = runCatching { withContext(Dispatchers.IO) { findNextPdfMatch(loader, id, password, page, query) } }.getOrNull()
                if (found == null) status = "No further text match" else { page = found; status = "Match on page ${found + 1}" }
            } }) { Text("Find next page") }
        }
        bitmap?.let { image -> Box(Modifier.fillMaxWidth().weight(1f).horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
            Image(image.asImageBitmap(), "PDF page ${page + 1}", Modifier.width(image.width.dp).height(image.height.dp).pointerInput(id) {
                detectTransformGestures { _, _, scale, _ -> zoom = (zoom * scale).coerceIn(0.25f, 4f) }
            }, contentScale = ContentScale.FillBounds)
        } }
        if (!pageText.isNullOrBlank()) SelectionContainer { Text(pageText!!, modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState())) }
    }
    if (passwordPrompt) AlertDialog(onDismissRequest = { passwordPrompt = false }, title = { Text("PDF password") },
        text = { OutlinedTextField(password, { password = it.take(256) }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation()) },
        confirmButton = { TextButton(onClick = { passwordPrompt = false; passwordAttempt++ }) { Text("Open") } },
        dismissButton = { TextButton(onClick = { passwordPrompt = false }) { Text("Cancel") } })
}

/** Scans one page at a time off the UI thread; coroutine cancellation stops a long search. */
private suspend fun findNextPdfMatch(loader: suspend (String, String) -> ParcelFileDescriptor?, id: String, password: String, currentPage: Int, query: String): Int? {
    if (Build.VERSION.SDK_INT < 35 || query.isBlank()) return null
    val descriptor = loader(id, "r") ?: return null
    val renderer = try { PdfAdvanced.open(descriptor, password) }
        catch (error: Exception) { descriptor.close(); throw error }
    renderer.use { pdf ->
        for (index in currentPage + 1 until pdf.pageCount) {
            currentCoroutineContext().ensureActive()
            pdf.openPage(index).use { if (PdfAdvanced.hasMatch(it, query)) return index }
        }
        for (index in 0 until currentPage.coerceAtMost(pdf.pageCount)) {
            currentCoroutineContext().ensureActive()
            pdf.openPage(index).use { if (PdfAdvanced.hasMatch(it, query)) return index }
        }
    }
    return null
}

/** Kept in a separate class so API 29–34 never resolve Android 15-only PDF symbols. */
@RequiresApi(35)
private object PdfAdvanced {
    fun open(descriptor: ParcelFileDescriptor, password: String): PdfRenderer =
        PdfRenderer(descriptor, LoadParams.Builder().apply { if (password.isNotBlank()) setPassword(password) }.build())

    fun pageText(page: PdfRenderer.Page): String = buildString {
        for (block in page.textContents) {
            if (length >= 100_000) break
            append(block.text.take(100_000 - length))
            if (length < 100_000) append('\n')
        }
    }

    fun hasMatch(page: PdfRenderer.Page, query: String): Boolean = page.searchText(query).isNotEmpty()
}
