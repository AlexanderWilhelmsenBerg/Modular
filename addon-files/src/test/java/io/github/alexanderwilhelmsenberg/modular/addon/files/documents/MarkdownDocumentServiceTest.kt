package io.github.alexanderwilhelmsenberg.modular.addon.files.documents

import org.junit.Assert.*
import org.junit.Test

class MarkdownDocumentServiceTest {
    @Test fun utf8BomAndCrLfRoundTrip() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "# Title\r\nbody\r\n".toByteArray()
        val (text, encoding) = MarkdownDocumentService.decode(bytes)
        assertEquals(MarkdownEncoding.UTF8_BOM, encoding)
        val ending = MarkdownDocumentService.detectLineEnding(text)
        assertEquals(MarkdownLineEnding.CRLF, ending)
        assertArrayEquals(bytes, MarkdownDocumentService.encode(text, encoding, ending))
        assertEquals("a\r\nb\r\n", MarkdownDocumentService.normalizeLineEndings("a\nb\n", ending))
    }

    @Test fun malformedUtf8IsRejected() {
        try {
            MarkdownDocumentService.decode(byteArrayOf(0xC3.toByte(), 0x28))
            fail("Malformed bytes should not be overwritten")
        } catch (expected: IllegalArgumentException) { }
    }

    @Test fun undoRedoDropsRedoAfterNewEdit() {
        val history = MarkdownUndoHistory("one")
        history.edit("two")
        history.edit("three")
        assertEquals("two", history.undo())
        assertEquals("three", history.redo())
        assertEquals("two", history.undo())
        history.edit("four")
        assertFalse(history.canRedo)
        assertEquals("four", history.text)
    }
}
