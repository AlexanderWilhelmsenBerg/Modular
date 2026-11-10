package io.github.alexanderwilhelmsenberg.modular.addon.files.storage

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class StorageSafetyTest {
    @Test fun traversalAndSymlinksCannotLeaveAuthorizedRoot() {
        val root = Files.createTempDirectory("files-root").toFile()
        val outside = Files.createTempDirectory("files-other").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) { StorageSafety.confinedChild(root, root, "..") }
            assertThrows(IllegalArgumentException::class.java) { StorageSafety.confinedChild(root, root, "../other") }
            val link = root.toPath().resolve("link")
            Files.createSymbolicLink(link, outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { StorageSafety.confinedChild(root, root, "link") }
            assertEquals(root.canonicalPath + "/safe", StorageSafety.confinedChild(root, root, "safe").path)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun newChildInvalidatesReviewedDirectory() {
        val reviewed = mapOf("folder" to setOf("a", "b"))
        assertTrue(StorageSafety.reviewedChildrenUnchanged(reviewed, reviewed))
        assertFalse(StorageSafety.reviewedChildrenUnchanged(reviewed, mapOf("folder" to setOf("a", "b", "new"))))
        assertFalse(StorageSafety.reviewedChildrenUnchanged(reviewed, mapOf("folder" to setOf("a"))))
    }

    @Test fun trashIndicatorUsesRecursiveFileCountAndEmptyFolderPresence() {
        assertEquals(TrashSummary.Color.GREY, TrashSummary(0, 0, 0, 0).color)
        assertEquals(TrashSummary.Color.BLUE, TrashSummary(0, 1, 0, 0).color)
        assertEquals(TrashSummary.Color.BLUE, TrashSummary(20, 0, 0, 0).color)
        assertEquals(TrashSummary.Color.RED, TrashSummary(21, 0, 0, 0).color)
    }

    @Test fun corruptedCopyIsRejectedBeforeCallerCanRemoveSource() {
        val source = "the only intact copy".toByteArray()
        val destination = ByteArrayOutputStream()
        val expected = VerifiedCopy.transfer(ByteArrayInputStream(source), destination, Cancellation { false }, {})
        val corrupt = destination.toByteArray().also { it[0] = (it[0] + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { VerifiedCopy.verify(ByteArrayInputStream(corrupt), expected) }
        assertArrayEquals("Source remains untouched after failed verification", "the only intact copy".toByteArray(), source)
    }

    @Test fun onlineTrashCacheCannotResurrectDeletedEntryButOfflineRecordRemains() {
        val id = "trashed-1"
        assertFalse(StorageSafety.retainTrashRecord("phone", id, setOf("phone"), emptySet(), emptySet()))
        assertTrue(StorageSafety.retainTrashRecord("usb", id, emptySet(), emptySet(), emptySet()))
        assertTrue(StorageSafety.retainTrashRecord("phone", id, setOf("phone"), setOf("phone:$id"), emptySet()))
        assertTrue(StorageSafety.retainTrashRecord("phone", id, setOf("phone"), emptySet(), setOf("phone:$id")))
        assertFalse(StorageSafety.retainTrashRecord("phone", id, setOf("phone"), setOf("usb:$id"), emptySet()))
    }
}
