package io.github.alexanderwilhelmsenberg.modular.organization

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.mostused.MostUsedSettingsStore
import io.github.alexanderwilhelmsenberg.modular.mostused.SavedAppKey
import io.github.alexanderwilhelmsenberg.modular.mostused.SavedSettings
import io.github.alexanderwilhelmsenberg.modular.mostused.encodeMostUsedSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationStoreTest {
    private val a = AppKey("example.a", "A", 1)
    private val b = AppKey("example.b", "B", 2)

    @Test fun legacyAppPinsMigrateInOrderAndFolderPlacementSharesTransaction() = runBlocking {
        val memory = MemoryPreferences()
        memory.setRaw("settings_json_v1", encodeMostUsedSettings(SavedSettings(
            pins = listOf(SavedAppKey.from(a), SavedAppKey.from(b)),
        )))
        val mostUsed = MostUsedSettingsStore(memory)
        assertFalse(mostUsed.currentSettings().pinsMigrationComplete)
        val store = OrganizationStore(memory)
        assertEquals(listOf(HomePinRef.App(a), HomePinRef.App(b)), store.currentSnapshot().pins)
        assertTrue(store.currentSnapshot().pinsMigrated)
        assertTrue(mostUsed.currentSettings().pinsMigrationComplete)
        mostUsed.setEnabled(true).getOrThrow()

        store.update(OrganizationBatch(listOf(
            OrganizationEdit.CreateFolder(FolderDefinition("f1", "Folder", listOf(a), FolderPlacement.MOST_USED)),
        ))).getOrThrow()
        assertEquals(listOf(HomePinRef.App(a), HomePinRef.App(b), HomePinRef.Folder("f1")),
            mostUsed.currentSettings().pinRefs)
        assertEquals(listOf(a, b), mostUsed.currentSettings().pinnedKeys)
        store.update(OrganizationBatch(listOf(OrganizationEdit.DeleteFolder("f1")))).getOrThrow()
        assertEquals(listOf(HomePinRef.App(a), HomePinRef.App(b)), mostUsed.currentSettings().pinRefs)
    }

    @Test fun pickerBatchIsAtomicWhenDestinationDisappears() = runBlocking {
        val store = OrganizationStore(MemoryPreferences())
        val start = store.currentSnapshot()
        val failed = store.update(OrganizationBatch(listOf(
            OrganizationEdit.CreateCategory(CategoryDefinition("c", "Custom", builtIn = false)),
            OrganizationEdit.SetMembership("deleted", a, MembershipOverride.INCLUDE),
        )))
        assertTrue(failed.isFailure)
        assertEquals(start.copy(pinsMigrated = true), store.currentSnapshot())
        val committed = store.update(OrganizationBatch(listOf(
            OrganizationEdit.CreateCategory(CategoryDefinition("c", "Custom", builtIn = false)),
            OrganizationEdit.SetMembership("c", a, MembershipOverride.INCLUDE),
        ))).getOrThrow()
        assertEquals(MembershipOverride.INCLUDE, committed.overrideFor("c", a))
        assertEquals(HomePageId.Category("c"), committed.pageOrder[1])
    }

    @Test fun undoChecksOnlyTheAffectedPair() = runBlocking {
        val store = OrganizationStore(MemoryPreferences())
        val first = store.setMembership(BuiltInCategories.GAMES, a, MembershipOverride.INCLUDE).getOrThrow()
        store.setMembership(BuiltInCategories.MEDIA, b, MembershipOverride.INCLUDE).getOrThrow()
        store.undoMembership(first).getOrThrow()
        assertEquals(MembershipOverride.AUTO, store.currentSnapshot().overrideFor(BuiltInCategories.GAMES, a))
        assertEquals(MembershipOverride.INCLUDE, store.currentSnapshot().overrideFor(BuiltInCategories.MEDIA, b))
        assertTrue(store.undoMembership(first).isFailure)

        val newer = store.setMembership(BuiltInCategories.GAMES, a, MembershipOverride.INCLUDE).getOrThrow()
        store.setMembership(BuiltInCategories.GAMES, a, MembershipOverride.EXCLUDE).getOrThrow()
        assertTrue(store.undoMembership(newer).isFailure)
    }

    @Test fun resetCategoryOverridesClearsOnlyThatCategory() = runBlocking {
        val store = OrganizationStore(MemoryPreferences())
        val first = store.setMembership(BuiltInCategories.GAMES, a, MembershipOverride.INCLUDE).getOrThrow()
        store.setMembership(BuiltInCategories.GAMES, b, MembershipOverride.EXCLUDE).getOrThrow()
        store.setMembership(BuiltInCategories.MEDIA, a, MembershipOverride.INCLUDE).getOrThrow()
        store.update(OrganizationBatch(listOf(
            OrganizationEdit.ResetCategoryOverrides(BuiltInCategories.GAMES),
        ))).getOrThrow()
        val snapshot = store.currentSnapshot()
        assertEquals(MembershipOverride.AUTO, snapshot.overrideFor(BuiltInCategories.GAMES, a))
        assertEquals(MembershipOverride.AUTO, snapshot.overrideFor(BuiltInCategories.GAMES, b))
        assertEquals(MembershipOverride.INCLUDE, snapshot.overrideFor(BuiltInCategories.MEDIA, a))
        assertTrue(store.undoMembership(first).isFailure)
    }

    @Test fun malformedDocumentKeepsLastGoodAndRejectsFurtherWrites() = runBlocking {
        val memory = MemoryPreferences()
        val store = OrganizationStore(memory)
        val good = store.currentSnapshot()
        memory.setRaw("organization_json_v1", "{broken")
        assertTrue(store.update(OrganizationBatch(listOf(
            OrganizationEdit.SetCategoriesEnabled(true)))).isFailure)
        assertFalse(store.error.value.isNullOrEmpty())
        assertEquals(good, store.snapshots.first())
    }

    @Test fun failedSaveLeavesLastGoodAndRevisionUntouched() = runBlocking {
        val memory = MemoryPreferences()
        val store = OrganizationStore(memory)
        val before = store.currentSnapshot()
        memory.failNextWrite = true
        assertTrue(store.update(OrganizationBatch(listOf(
            OrganizationEdit.SetCategoriesEnabled(true)))).isFailure)
        assertEquals(before, store.currentSnapshot())
        assertEquals(before.revision, store.currentSnapshot().revision)
    }

    @Test fun stalePickerRevisionCannotOverwriteConcurrentEditorChanges() = runBlocking {
        val memory = MemoryPreferences()
        val first = OrganizationStore(memory)
        val second = OrganizationStore(memory)
        val stale = first.currentSnapshot()
        second.update(OrganizationBatch(listOf(
            OrganizationEdit.UpdateCategory(stale.categories.first().copy(name = "Games renamed")),
        ))).getOrThrow()
        val result = first.update(OrganizationBatch(listOf(
            OrganizationEdit.CreateCategory(CategoryDefinition("c", "Custom", builtIn = false)),
        ), expectedRevision = stale.revision))
        assertTrue(result.isFailure)
        assertEquals("Games renamed", first.currentSnapshot().categories.first().name)
        assertFalse(first.currentSnapshot().categories.any { it.id == "c" })
    }

    @Test fun folderIconRoundTripsAcrossStoreInstances() = runBlocking {
        val memory = MemoryPreferences()
        val store = OrganizationStore(memory)
        store.update(OrganizationBatch(listOf(
            OrganizationEdit.CreateFolder(FolderDefinition("f1", "Music", listOf(a), iconId = "music")),
        ))).getOrThrow()

        assertEquals("music", OrganizationStore(memory).currentSnapshot().folders.single().iconId)
        assertTrue(memory.raw("organization_json_v1")!!.contains("\"iconId\":\"music\""))
    }

    @Test fun oldFolderWithoutIconLoadsAndUnknownFutureIconSurvivesEdits() = runBlocking {
        val memory = MemoryPreferences()
        val store = OrganizationStore(memory)
        store.update(OrganizationBatch(listOf(
            OrganizationEdit.CreateCategory(CategoryDefinition("custom", "Custom", builtIn = false)),
            OrganizationEdit.CreateFolder(FolderDefinition("f1", "Music", listOf(a), iconId = "music")),
        ))).getOrThrow()
        val key = "organization_json_v1"
        val saved = requireNotNull(memory.raw(key))
        val iconField = ",\"iconId\":\"music\""
        assertTrue(saved.contains(iconField))

        memory.setRaw(key, saved.replace(iconField, ""))
        val old = OrganizationStore(memory).currentSnapshot()
        assertEquals("folder", old.folders.single().iconId)
        assertEquals(listOf(a), old.folders.single().members)
        assertTrue(old.categories.any { it.id == "custom" })

        memory.setRaw(key, saved.replace("\"iconId\":\"music\"", "\"iconId\":\"future-icon\""))
        val newer = OrganizationStore(memory)
        assertEquals("future-icon", newer.currentSnapshot().folders.single().iconId)
        newer.update(OrganizationBatch(listOf(
            OrganizationEdit.UpdateCategory(newer.currentSnapshot().categories.first { it.id == "custom" }
                .copy(name = "Renamed")),
        ))).getOrThrow()
        val after = OrganizationStore(memory).currentSnapshot()
        assertEquals("future-icon", after.folders.single().iconId)
        assertEquals(listOf(a), after.folders.single().members)
        assertTrue(after.categories.any { it.id == "custom" && it.name == "Renamed" })
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val mutable = MutableStateFlow<Preferences>(emptyPreferences())
        var failNextWrite = false
        override val data: StateFlow<Preferences> = mutable
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failNextWrite) {
                failNextWrite = false
                throw IllegalStateException("disk full")
            }
            val next = transform(mutable.value.toMutablePreferences())
            mutable.value = next
            return next
        }
        fun setRaw(key: String, value: String) {
            mutable.value = mutable.value.toMutablePreferences().apply {
                this[stringPreferencesKey(key)] = value
            }
        }
        fun raw(key: String): String? = mutable.value[stringPreferencesKey(key)]
    }
}
