package io.github.alexanderwilhelmsenberg.modular.addon.files.storage

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DirectoryListingCacheTest {
    private fun entry(folder: String, name: String) = FileEntry("$folder.$name", "phone", folder,
        name, name, false, "text/plain", 4, 12, null, true, true)

    @Test fun persistsAcrossInstancesAndHonorsAgeAndInvalidation() {
        val directory = Files.createTempDirectory("listing-cache").toFile()
        try {
            val first = DirectoryListingCache(directory)
            first.write("folder", listOf(entry("folder", "note.txt")), 1_000)
            val second = DirectoryListingCache(directory)
            assertEquals("note.txt", second.read("folder", 1_100, 200)?.entries?.single()?.name)
            assertNull(second.read("folder", 1_201, 200))
            assertNull(second.read("other", 1_100, 200))
            second.invalidate("folder")
            assertNull(first.read("folder", 1_100, 200))
        } finally { directory.deleteRecursively() }
    }

    @Test fun oversizedAndCorruptSnapshotsNeverBecomePartialListings() {
        val directory = Files.createTempDirectory("listing-cache").toFile()
        try {
            val cache = DirectoryListingCache(directory)
            cache.write("folder", listOf(entry("folder", "ok")), 1_000)
            cache.write("folder", (0..2_000).map { entry("folder", "n$it") }, 1_100)
            assertNull(cache.read("folder", 1_100, 200))
            cache.write("folder", listOf(entry("folder", "ok")), 1_200)
            directory.listFiles()!!.single().writeText("{broken")
            assertNull(cache.read("folder", 1_200, 200))
        } finally { directory.deleteRecursively() }
    }

    @Test fun folderCacheCannotContainEntriesFromAnotherParent() {
        val directory = Files.createTempDirectory("listing-cache").toFile()
        try {
            val cache = DirectoryListingCache(directory)
            cache.write("folder", listOf(entry("other", "unrelated")), 1_000)
            assertNull(cache.read("folder", 1_100, 200))
        } finally { directory.deleteRecursively() }
    }

    @Test fun persistentFolderCountIsBounded() {
        val directory = Files.createTempDirectory("listing-cache").toFile()
        try {
            val cache = DirectoryListingCache(directory)
            repeat(40) { index -> cache.write("folder-$index", emptyList(), 1_000L + index) }
            assertEquals(32, directory.listFiles { file -> file.extension == "json" }?.size ?: 0)
        } finally { directory.deleteRecursively() }
    }
}
