package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import io.github.alexanderwilhelmsenberg.modular.filesui.documents.FilesDocumentViewer
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** A surface has no ActivityResultRegistryOwner; Save as preserves the draft before handoff. */
class FilesMarkdownSurfaceHandoffTest {
    @get:Rule val rule = createComposeRule()

    @Test fun markdownEditorRendersWithoutActivityAndPreservesDraftBeforeHandoff() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "markdown-surface-${UUID.randomUUID()}"
        val document = File(context.cacheDir, "$id.md")
        val draft = File(File(context.filesDir, "files-native-drafts"), sha256(id) + ".draft")
        var handedOff: String? = null
        try {
            document.writeText("# Original\n")
            rule.setContent {
                CompositionLocalProvider(
                    LocalContext provides context,
                ) {
                    MaterialTheme {
                        FilesDocumentViewer(documentId = id, kind = "markdown", baseFingerprint = "unused",
                            documentLoader = { _, mode -> withContext(Dispatchers.IO) {
                                if (mode == "r") ParcelFileDescriptor.open(document, ParcelFileDescriptor.MODE_READ_ONLY)
                                else null
                            } }, onCommit = { false }, onCancel = {}, onClose = {},
                            onOpenInActivity = { handedOff = it })
                    }
                }
            }
            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithText("Original").assertExists() }.isSuccess
            }
            rule.onNodeWithText("Edit").performClick()
            rule.onNodeWithTag("files.document.markdown.editor").performTextReplacement("# Edited\n")
            rule.onNodeWithText("Open in Files to save a copy").assertExists()
            rule.onNodeWithTag("files.document.markdown.saveAs").performClick()
            rule.waitUntil(15_000) { handedOff == id && draft.isFile }
            assertEquals(id, handedOff)
            DataInputStream(FileInputStream(draft)).use { input ->
                assertEquals(sha256("# Original\n"), input.readUTF())
                val bytes = ByteArray(input.readInt())
                input.readFully(bytes)
                assertEquals("# Edited\n", String(bytes))
            }
        } finally {
            draft.delete()
            document.delete()
        }
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
