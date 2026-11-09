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
import io.github.alexanderwilhelmsenberg.modular.workspace.ListeningPreferences
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePage
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePageId
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePreferences
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceSection
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    @Test fun versionOneOrganizationMigratesWithoutChangingLegacyPageOrder() = runBlocking {
        val memory = MemoryPreferences()
        val store = OrganizationStore(memory)
        val before = store.update(OrganizationBatch(listOf(
            OrganizationEdit.CreateCategory(CategoryDefinition("widget:legacy", "Legacy", builtIn = false)),
            OrganizationEdit.SetCategoriesEnabled(true),
        ))).getOrThrow()
        val key = "organization_json_v1"
        val saved = Json.parseToJsonElement(requireNotNull(memory.raw(key))) as JsonObject
        val versionOne = JsonObject(saved.filterKeys { it !in setOf(
            "workspacePages", "workspacePreferences", "widgets", "listeningPreferences") } +
            ("version" to JsonPrimitive(1))).toString()
        memory.setRaw(key, JsonObject((Json.parseToJsonElement(versionOne) as JsonObject)
            .filterKeys { it != "version" }).toString())
        assertEquals(before.pageOrder, OrganizationStore(memory).currentSnapshot().pageOrder)
        memory.setRaw(key, versionOne)

        val migrated = OrganizationStore(memory)
        assertEquals(before.pageOrder, migrated.currentSnapshot().pageOrder)
        assertEquals(HomePageId.Category("widget:legacy"), migrated.currentSnapshot().pageOrder[1])
        migrated.update(OrganizationBatch(listOf(
            OrganizationEdit.SetWorkspacePreferences(WorkspacePreferences(showBattery = true)),
        ))).getOrThrow()
        assertTrue(requireNotNull(memory.raw(key)).contains("\"version\":2"))
        assertTrue(OrganizationStore(memory).currentSnapshot().workspacePreferences.showBattery)
    }

    @Test fun workspacePagesAndWidgetsRoundTripAndPageDeletionRemovesOnlyOwnedWidgets() = runBlocking {
        val memory = MemoryPreferences()
        val store = OrganizationStore(memory)
        val homeWidget = WorkspaceWidget(1, WorkspacePageId.HOME, "example.home/.Widget", 180,
            hostToken = "device-local-host-a")
        val pageWidget = WorkspaceWidget(2, WorkspacePageId.widget("tools"), "example.tools/.Widget", 240,
            hostToken = "device-local-host-a")
        val preferences = WorkspacePreferences(sectionOrder = listOf(
            WorkspaceSection.WIDGETS, WorkspaceSection.CLOCK, WorkspaceSection.MOST_USED,
            WorkspaceSection.LISTENING))
        val startingRevision = store.currentSnapshot().revision
        store.update(OrganizationBatch(listOf(
            OrganizationEdit.CreateWorkspacePage(WorkspacePage("tools", "Tools")),
            OrganizationEdit.SetWidgets(listOf(homeWidget, pageWidget)),
            OrganizationEdit.SetWorkspacePreferences(preferences),
            OrganizationEdit.SetListeningPreferences(ListeningPreferences(true, HeadsetKind.USB, listOf(a, b))),
        ), expectedRevision = startingRevision)).getOrThrow()
        val restored = OrganizationStore(memory).currentSnapshot()
        assertEquals(listOf(homeWidget, pageWidget), restored.widgets)
        assertTrue(requireNotNull(memory.raw("organization_json_v1"))
            .contains("\"hostToken\":\"device-local-host-a\""))
        assertEquals(preferences, restored.workspacePreferences)
        assertEquals(listOf(a, b), restored.listeningPreferences.apps)
        assertTrue(HomePageId.Widget("tools") in restored.visiblePageOrder())

        val categoriesOff = store.update(OrganizationBatch(listOf(
            OrganizationEdit.SetCategoriesEnabled(false),
        ))).getOrThrow()
        assertTrue(HomePageId.Widget("tools") in categoriesOff.visiblePageOrder())

        store.update(OrganizationBatch(listOf(
            OrganizationEdit.DeleteWorkspacePage("tools"),
        ))).getOrThrow()
        val deleted = OrganizationStore(memory).currentSnapshot()
        assertEquals(listOf(HomePageId.Home), deleted.visiblePageOrder())
        assertEquals(listOf(homeWidget), deleted.widgets)
        assertEquals(listOf(a, b), deleted.listeningPreferences.apps)
    }

    @Test fun invalidWidgetUpdateIsAtomicAndExistingPlacementsRemain() = runBlocking {
        val store = OrganizationStore(MemoryPreferences())
        val good = WorkspaceWidget(1, WorkspacePageId.HOME, "example.home/.Widget", 180)
        store.update(OrganizationBatch(listOf(OrganizationEdit.SetWidgets(listOf(good))),
            expectedRevision = store.currentSnapshot().revision)).getOrThrow()
        val before = store.currentSnapshot()
        assertTrue(store.update(OrganizationBatch(listOf(
            OrganizationEdit.CreateWorkspacePage(WorkspacePage("tools", "Tools")),
            OrganizationEdit.SetWidgets(listOf(good, good.copy(appWidgetId = 2, pageId = "widget:missing"))),
        ), expectedRevision = before.revision)).isFailure)
        assertEquals(before, store.currentSnapshot())
    }

    @Test fun staleWidgetLayoutCannotReplaceConcurrentWidget() = runBlocking {
        val memory = MemoryPreferences()
        val first = OrganizationStore(memory)
        val second = OrganizationStore(memory)
        val stale = first.currentSnapshot()
        val added = WorkspaceWidget(1, WorkspacePageId.HOME, "example.home/.Widget", 180)
        second.update(OrganizationBatch(listOf(OrganizationEdit.SetWidgets(listOf(added))),
            expectedRevision = stale.revision)).getOrThrow()

        val replacement = WorkspaceWidget(2, WorkspacePageId.HOME, "example.other/.Widget", 180)
        assertTrue(first.update(OrganizationBatch(listOf(OrganizationEdit.SetWidgets(listOf(replacement))),
            expectedRevision = stale.revision)).isFailure)
        assertEquals(listOf(added), first.currentSnapshot().widgets)
        assertTrue(first.update(OrganizationBatch(listOf(OrganizationEdit.SetWidgets(listOf(replacement))))).isFailure)
    }

    @Test fun widgetHostTokenIsBoundedAndLegacyPlacementLoads() = runBlocking {
        val memory = MemoryPreferences()
        val store = OrganizationStore(memory)
        val placement = WorkspaceWidget(1, WorkspacePageId.HOME, "example.home/.Widget", 180,
            hostToken = "original-host")
        store.update(OrganizationBatch(listOf(OrganizationEdit.SetWidgets(listOf(placement))),
            expectedRevision = store.currentSnapshot().revision)).getOrThrow()
        val saved = requireNotNull(memory.raw("organization_json_v1"))
        memory.setRaw("organization_json_v1", saved.replace(",\"hostToken\":\"original-host\"", ""))
        val legacy = OrganizationStore(memory).currentSnapshot()
        assertEquals("", legacy.widgets.single().hostToken)
        assertTrue(store.update(OrganizationBatch(listOf(OrganizationEdit.SetWidgets(listOf(
            placement.copy(hostToken = "x".repeat(129))))),
            expectedRevision = legacy.revision)).isFailure)
        assertEquals("", OrganizationStore(memory).currentSnapshot().widgets.single().hostToken)
    }

    @Test fun unsupportedWorkspaceVersionKeepsLastGoodWithoutResettingStorage() = runBlocking {
        val memory = MemoryPreferences()
        val store = OrganizationStore(memory)
        store.update(OrganizationBatch(listOf(OrganizationEdit.SetCategoriesEnabled(true)))).getOrThrow()
        val good = store.currentSnapshot()
        val raw = requireNotNull(memory.raw("organization_json_v1"))
        val unsupported = raw.replace("\"version\":2", "\"version\":99")
        memory.setRaw("organization_json_v1", unsupported)
        assertTrue(store.update(OrganizationBatch(listOf(
            OrganizationEdit.SetWorkspacePreferences(WorkspacePreferences(showBattery = true)),
        ))).isFailure)
        assertFalse(store.error.value.isNullOrEmpty())
        assertEquals(unsupported, memory.raw("organization_json_v1"))
        assertEquals(good, store.snapshots.first())
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
