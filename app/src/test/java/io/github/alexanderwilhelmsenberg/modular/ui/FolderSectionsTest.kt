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
    private val three = FolderDefinition("three", "Three")

    @Test fun mixedSavedPinsOrderMostUsedAppsWithoutFolderRows() {
        val entries = featuredHomeEntries(listOf(suggested, pinned),
            listOf(HomePinRef.Folder("two"), HomePinRef.App(pinned.key), HomePinRef.Folder("one")), true)
        assertEquals(listOf(HomeListEntry.Header("★"), HomeListEntry.App(pinned, true),
            HomeListEntry.App(suggested, true), HomeListEntry.FeaturedStatus), entries)
    }

    @Test fun emptyFeaturedAppsShowPinPrompt() {
        assertEquals(listOf(HomeListEntry.Header("★"), HomeListEntry.FeaturedEmpty),
            featuredHomeEntries(emptyList(), listOf(HomePinRef.Folder("one")), false))
    }

    @Test fun emptyFolderGroupHasNoHeader() {
        assertEquals(listOf(HomeListEntry.Header("★"), HomeListEntry.App(pinned, true)),
            featuredHomeEntries(listOf(pinned), emptyList(), false))
        assertTrue(featuredHomeEntries(null, emptyList(), false).isEmpty())
    }

    @Test fun drawerPlacesOneDeduplicatedFoldersSectionBetweenMostUsedAndAlphabet() {
        val pins = listOf(HomePinRef.Folder("two"), HomePinRef.App(pinned.key), HomePinRef.Folder("one"))
        val folders = mergedDrawerFolders(listOf(one, two), listOf(one, three), pins)
        val entries = drawerSectionEntries(listOf(pinned, suggested), folders, pins, true,
            listOf(HomeSection("P", listOf(pinned)), HomeSection("S", listOf(suggested))))

        assertEquals(listOf(two, one, three), folders)
        assertEquals(listOf(
            HomeListEntry.Header("★"), HomeListEntry.App(pinned, true),
            HomeListEntry.App(suggested, true), HomeListEntry.FeaturedStatus,
            HomeListEntry.Header("Folders"), HomeListEntry.Folder(two),
            HomeListEntry.Folder(one), HomeListEntry.Folder(three),
            HomeListEntry.Header("P"), HomeListEntry.App(pinned),
            HomeListEntry.Header("S"), HomeListEntry.App(suggested),
        ), entries)
        assertEquals(1, entries.count { it == HomeListEntry.Header("Folders") })
    }

    @Test fun foldersRemainBeforeAlphabetWhenMostUsedAppsAreUnavailable() {
        val folders = mergedDrawerFolders(listOf(two), listOf(one), emptyList())
        val entries = drawerSectionEntries(null, folders, emptyList(), false,
            listOf(HomeSection("P", listOf(pinned))))
        assertEquals(listOf(HomeListEntry.Header("Folders"), HomeListEntry.Folder(two),
            HomeListEntry.Folder(one), HomeListEntry.Header("P"), HomeListEntry.App(pinned)), entries)
        assertFalse(entries.any { it == HomeListEntry.Header("★") })
    }
}
