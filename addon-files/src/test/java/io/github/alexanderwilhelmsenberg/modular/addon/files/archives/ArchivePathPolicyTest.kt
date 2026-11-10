package io.github.alexanderwilhelmsenberg.modular.addon.files.archives

import org.junit.Assert.*
import org.junit.Test

class ArchivePathPolicyTest {
    @Test fun acceptsRelativeNestedPaths() {
        assertEquals(listOf("photos", "image.jpg"), ArchivePathPolicy.segments("photos/image.jpg"))
    }

    @Test fun rejectsTraversalAndAbsolutePaths() {
        for (path in listOf("../escape", "folder/../escape", "/absolute", "C:\\absolute", "\\\\server\\share", "folder//empty", "folder/./same", "folder/\u0000bad")) {
            try { ArchivePathPolicy.segments(path); fail("Accepted $path") }
            catch (expected: IllegalArgumentException) { }
        }
    }

    @Test fun supportedExtensionsAreExplicit() {
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveExtractor.format("backup.tar.gz"))
        assertEquals(ArchiveFormat.RAR, ArchiveExtractor.format("backup.RAR"))
        try { ArchiveExtractor.format("unknown.exe"); fail("Unsupported") }
        catch (expected: UnsupportedOperationException) { }
    }
}
