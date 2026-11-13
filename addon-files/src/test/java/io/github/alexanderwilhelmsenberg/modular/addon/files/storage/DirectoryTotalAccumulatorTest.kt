package io.github.alexanderwilhelmsenberg.modular.addon.files.storage

import org.junit.Assert.*
import org.junit.Test

class DirectoryTotalAccumulatorTest {
    @Test fun singleTraversalProvidesParentAndNestedFolderSizes() {
        val sizes = DirectoryTotalAccumulator("root")
        sizes.discover("root", "child"); sizes.discover("child", "deep")
        sizes.file("root", 2); sizes.file("child", 3); sizes.file("deep", 5)
        listOf("root", "child", "deep").forEach(sizes::listed)
        val totals = sizes.finish(100)
        assertEquals(10L, totals.getValue("root").bytes)
        assertEquals(8L, totals.getValue("child").bytes)
        assertEquals(5L, totals.getValue("deep").bytes)
        assertEquals(3L, totals.getValue("root").fileCount)
        assertTrue(totals.values.all { it.complete })
    }

    @Test fun unknownAndUnvisitedBranchesNeverBecomeCompleteZeroes() {
        val sizes = DirectoryTotalAccumulator("root")
        sizes.discover("root", "unknown"); sizes.discover("root", "unvisited")
        sizes.file("unknown", null)
        sizes.listed("root"); sizes.listed("unknown")
        val totals = sizes.finish(100)
        assertFalse(totals.getValue("unknown").complete)
        assertFalse(totals.getValue("unvisited").complete)
        assertFalse(totals.getValue("root").complete)
        assertEquals(1L, totals.getValue("root").fileCount)
    }

    @Test fun repeatedFolderIsPartialAndCacheOutputIsBounded() {
        val sizes = DirectoryTotalAccumulator("root")
        repeat(150) { sizes.discover("root", "child-$it"); sizes.listed("child-$it") }
        assertFalse(sizes.discover("child-0", "root"))
        sizes.listed("root")
        val totals = sizes.finish(100)
        assertEquals(128, totals.size)
        assertFalse(totals.getValue("child-0").complete)
        assertFalse(totals.getValue("root").complete)
    }
}
