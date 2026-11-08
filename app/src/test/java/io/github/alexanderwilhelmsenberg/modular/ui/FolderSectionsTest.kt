package io.github.alexanderwilhelmsenberg.modular.ui

import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.organization.FolderDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.HomePinRef
import org.junit.Assert.*
import org.junit.Test

class FolderSectionsTest {
    private val pinned = LaunchableApp(AppKey("pinned", "Main", 0), "Pinned", 0, true, null)
    private val suggested = LaunchableApp(AppKey("suggested", "Main", 0), "Suggested", 0, true, null)
    private val one = FolderDefinition("one", "One")
    private val two = FolderDefinition("two", "Two")

    @Test fun mixedSavedPinsRenderFoldersAfterSuggestionsAndStatus() {
        val entries = featuredHomeEntries(listOf(pinned, suggested), listOf(one, two),
            listOf(HomePinRef.Folder("two"), HomePinRef.App(pinned.key), HomePinRef.Folder("one")), true)
        assertEquals(listOf(HomeListEntry.Header("★"), HomeListEntry.App(pinned, true),
            HomeListEntry.App(suggested, true), HomeListEntry.FeaturedStatus,
            HomeListEntry.Header("Folders", "featured-folders"), HomeListEntry.Folder(two), HomeListEntry.Folder(one)), entries)
        assertNotEquals(HomeListEntry.Header("Folders").id, entries[4].id)
    }

    @Test fun onlyFoldersStillHasNamedSectionAndMissingPinsAreIgnored() {
        val entries = featuredHomeEntries(emptyList(), listOf(one),
            listOf(HomePinRef.Folder("removed"), HomePinRef.Folder("one")), false)
        assertEquals(listOf(HomeListEntry.Header("★"), HomeListEntry.Header("Folders", "featured-folders"),
            HomeListEntry.Folder(one)), entries)
    }

    @Test fun emptyFolderGroupHasNoHeader() {
        assertEquals(listOf(HomeListEntry.Header("★"), HomeListEntry.App(pinned, true)),
            featuredHomeEntries(listOf(pinned), emptyList(), emptyList(), false))
        assertTrue(featuredHomeEntries(null, emptyList(), emptyList(), false).isEmpty())
    }
}
