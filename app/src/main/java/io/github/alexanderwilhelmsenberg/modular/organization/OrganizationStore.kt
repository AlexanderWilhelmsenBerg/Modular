package io.github.alexanderwilhelmsenberg.modular.organization

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.mostused.MostUsedSettingsStore
import io.github.alexanderwilhelmsenberg.modular.mostused.SavedPinRef
import io.github.alexanderwilhelmsenberg.modular.mostused.coreMostUsedDataStore
import io.github.alexanderwilhelmsenberg.modular.mostused.decodeMostUsedSettings
import io.github.alexanderwilhelmsenberg.modular.mostused.encodeMostUsedSettings
import io.github.alexanderwilhelmsenberg.modular.mostused.mostUsedSettingsKey
import io.github.alexanderwilhelmsenberg.modular.workspace.ListeningPreferences
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePage
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePageId
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePreferences
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceSection
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val organizationKey = stringPreferencesKey("organization_json_v1")
private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

/** Limits prevent a malformed preference or picker batch from exhausting Home. */
object OrganizationLimits {
    const val MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
    const val MAX_CATEGORIES = 64
    const val MAX_FOLDERS = 128
    const val MAX_MEMBERS_PER_FOLDER = 2_000
    const val MAX_OVERRIDES = 20_000
    const val MAX_NAME_CODE_POINTS = 60
    const val MAX_ID_LENGTH = 128
    const val MAX_CONDITIONS_PER_FOLDER = 16
    const val MAX_WORKSPACE_PAGES = 32
    const val MAX_MODULE_PAGES = 16
    const val MAX_WIDGETS = 128
    const val MAX_LISTENING_APPS = 64
    const val MIN_WIDGET_HEIGHT_DP = 48
    const val MAX_WIDGET_HEIGHT_DP = 2_048
    const val MIN_WIDGET_AREA_WIDTH_PERCENT = 50
    const val MAX_WIDGET_AREA_WIDTH_PERCENT = 100
    const val MAX_WIDGET_MARGIN_DP = 48
    const val MAX_WIDGET_GAP_DP = 48
    const val MAX_WIDGET_PADDING_DP = 32
    const val MIN_GRID_COLUMNS = 1
    const val MAX_GRID_COLUMNS = 12
    const val MIN_GRID_ROW_HEIGHT_DP = 32
    const val MAX_GRID_ROW_HEIGHT_DP = 256
    const val MAX_WIDGET_GRID_ROW_SPAN = 32
    const val MAX_WIDGET_GRID_ROW = 256
}

/** Shares the single core_most_used DataStore with MostUsedSettingsStore. */
class OrganizationStore internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.coreMostUsedDataStore)

    private val mutableError = MutableStateFlow<String?>(null)
    private val lastGood = MutableStateFlow(OrganizationSnapshot())
    val error: StateFlow<String?> = mutableError

    val snapshots: Flow<OrganizationSnapshot> = flow {
        // One idempotent migration, committed before a snapshot is shown. Legacy pins
        // are also decoded directly so a failed migration never temporarily loses them.
        ensurePinMigration()
        emitAll(dataStore.data.map { preferences ->
            decodeSnapshot(preferences).also {
                lastGood.value = it
                mutableError.value = null
            }
        })
    }.retryWhen { failure, _ ->
        if (failure is CancellationException) return@retryWhen false
        mutableError.value = "Could not load organization settings: ${failure.message ?: failure.javaClass.simpleName}"
        emit(lastGood.value)
        delay(1_000)
        true
    }

    suspend fun currentSnapshot(): OrganizationSnapshot = snapshots.first()

    suspend fun ensurePinMigration(): Result<Unit> = try {
        dataStore.edit { preferences ->
            val settings = decodeMostUsedSettings(preferences[mostUsedSettingsKey])
            if (settings.pinRefs == null) {
                preferences[mostUsedSettingsKey] = encodeMostUsedSettings(
                    settings.copy(pinRefs = settings.effectivePins(), pinSchemaVersion = 2),
                )
            }
        }
        Result.success(Unit)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        mutableError.value = "Could not migrate Most Used pins: ${failure.message ?: failure.javaClass.simpleName}"
        Result.failure(failure)
    }

    suspend fun update(batch: OrganizationBatch): Result<OrganizationSnapshot> =
        mutate { current ->
            require(batch.expectedRevision != null || batch.edits.none { it is OrganizationEdit.SetWidgets }) {
                "Widget edits require the revision of the layout being changed"
            }
            require(batch.expectedRevision == null || batch.expectedRevision == current.revision) {
                "Organization changed; review and retry"
            }
            batch.edits.fold(current) { state, edit -> applyEdit(state, edit) }
        }.mapCatching { it }

    suspend fun setMembership(categoryId: String, appKey: AppKey,
        override: MembershipOverride): Result<MembershipChange> {
        var change: MembershipChange? = null
        val saved = mutate { snapshot ->
            if (snapshot.categories.none { it.id == categoryId })
                throw OrganizationDestinationMissingException("Category no longer exists")
            require(validAppKey(appKey)) { "Invalid app identity" }
            val pair = MembershipKey(categoryId, appKey)
            val previous = snapshot.overrideFor(categoryId, appKey)
            val next = if (previous == override) snapshot else applyMembership(snapshot, pair, override)
            change = MembershipChange(categoryId, appKey, previous, override,
                next.membershipTokens[pair] ?: 0L)
            next
        }
        return saved.map { requireNotNull(change) }
    }

    suspend fun undoMembership(change: MembershipChange): Result<Unit> = mutate { snapshot ->
        if (snapshot.categories.none { it.id == change.categoryId })
            throw OrganizationDestinationMissingException("Category no longer exists")
        val pair = MembershipKey(change.categoryId, change.appKey)
        if (snapshot.overrideFor(change.categoryId, change.appKey) != change.current ||
            snapshot.membershipTokens[pair] != change.token)
            throw OrganizationConflictException("Membership changed since this assignment")
        applyMembership(snapshot, pair, change.previous)
    }.map { Unit }

    private suspend fun mutate(transform: (OrganizationSnapshot) -> OrganizationSnapshot): Result<OrganizationSnapshot> {
        var committed: OrganizationSnapshot? = null
        return try {
            dataStore.edit { preferences ->
                val before = decodeSnapshot(preferences)
                val changed = transform(before)
                val next = if (changed == before) before else changed.copy(revision = before.revision + 1)
                val settings = decodeMostUsedSettings(preferences[mostUsedSettingsKey])
                validate(next)
                val encoded = encodeOrganization(next)
                require(encoded.toByteArray(Charsets.UTF_8).size <= OrganizationLimits.MAX_DOCUMENT_BYTES) {
                    "Organization storage limit reached"
                }
                preferences[organizationKey] = encoded
                val refs = next.pins.map(SavedPinRef::from)
                if (settings.pinRefs != refs) {
                    preferences[mostUsedSettingsKey] = encodeMostUsedSettings(
                        settings.copy(pinRefs = refs, pinSchemaVersion = 2,
                            pinsTouched = settings.pinsTouched ||
                            before.pins != next.pins),
                    )
                }
                committed = next.copy(pinsMigrated = true)
            }
            val result = requireNotNull(committed)
            lastGood.value = result
            mutableError.value = null
            Result.success(result)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutableError.value = "Could not save organization settings: ${failure.message ?: failure.javaClass.simpleName}"
            Result.failure(failure)
        }
    }

    private fun applyEdit(state: OrganizationSnapshot, edit: OrganizationEdit): OrganizationSnapshot = when (edit) {
        is OrganizationEdit.SetCategoriesEnabled -> state.copy(categoriesEnabled = edit.enabled)
        is OrganizationEdit.CreateCategory -> {
            val category = edit.category
            require(!category.builtIn && category.id != "home" && category.id !in BuiltInCategories.ids &&
                state.workspacePages.none { WorkspacePageId.widget(it.id) == category.id } &&
                state.categories.none { it.id == category.id }) { "Category ID already exists" }
            val order = state.pageOrder.toMutableList()
            val position = edit.position ?: (order.indexOf(HomePageId.Home) + 1)
            require(position in 0..order.size) { "Invalid page position" }
            order.add(position, HomePageId.Category(category.id))
            state.copy(categories = state.categories + category, pageOrder = order)
        }
        is OrganizationEdit.UpdateCategory -> {
            val old = state.categories.firstOrNull { it.id == edit.category.id }
                ?: error("Category no longer exists")
            require(old.builtIn == edit.category.builtIn) { "Category type cannot change" }
            state.copy(categories = state.categories.map { if (it.id == old.id) edit.category else it })
        }
        is OrganizationEdit.DeleteCategory -> {
            val category = state.categories.firstOrNull { it.id == edit.id }
                ?: error("Category no longer exists")
            require(!category.builtIn) { "Built-in categories cannot be deleted" }
            state.copy(categories = state.categories - category,
                pageOrder = state.pageOrder - HomePageId.Category(edit.id),
                overrides = state.overrides.filterKeys { it.categoryId != edit.id },
                membershipTokens = state.membershipTokens.filterKeys { it.categoryId != edit.id })
        }
        is OrganizationEdit.SetPageOrder -> state.copy(pageOrder = edit.order)
        is OrganizationEdit.SetMembership -> {
            if (state.categories.none { it.id == edit.categoryId })
                throw OrganizationDestinationMissingException("Category no longer exists")
            require(validAppKey(edit.appKey)) { "Invalid app identity" }
            applyMembership(state, MembershipKey(edit.categoryId, edit.appKey), edit.override)
        }
        is OrganizationEdit.ResetCategoryOverrides -> {
            if (state.categories.none { it.id == edit.categoryId })
                throw OrganizationDestinationMissingException("Category no longer exists")
            state.overrides.keys.filter { it.categoryId == edit.categoryId }
                .fold(state) { current, pair ->
                    applyMembership(current, pair, MembershipOverride.AUTO)
                }
        }
        is OrganizationEdit.CreateFolder -> {
            require(state.folders.none { it.id == edit.folder.id }) { "Folder ID already exists" }
            state.copy(folders = state.folders + edit.folder,
                pins = if (edit.folder.placement == FolderPlacement.MOST_USED)
                    state.pins + HomePinRef.Folder(edit.folder.id) else state.pins)
        }
        is OrganizationEdit.UpdateFolder -> {
            val old = state.folders.firstOrNull { it.id == edit.folder.id }
                ?: error("Folder no longer exists")
            val pin = HomePinRef.Folder(old.id)
            val pins = when {
                old.placement != FolderPlacement.MOST_USED && edit.folder.placement == FolderPlacement.MOST_USED ->
                    state.pins + pin
                old.placement == FolderPlacement.MOST_USED && edit.folder.placement != FolderPlacement.MOST_USED ->
                    state.pins - pin
                else -> state.pins
            }
            state.copy(folders = state.folders.map { if (it.id == old.id) edit.folder else it }, pins = pins)
        }
        is OrganizationEdit.DeleteFolder -> {
            require(state.folders.any { it.id == edit.id }) { "Folder no longer exists" }
            state.copy(folders = state.folders.filterNot { it.id == edit.id },
                pins = state.pins - HomePinRef.Folder(edit.id))
        }
        is OrganizationEdit.SetPins -> {
            require(edit.order.size == state.pins.size && edit.order.toSet() == state.pins.toSet()) {
                "Pin order must contain every saved pin exactly once"
            }
            state.copy(pins = edit.order)
        }
        is OrganizationEdit.CreateWorkspacePage -> {
            require(state.workspacePages.none { it.id == edit.page.id } &&
                state.categories.none { it.id == WorkspacePageId.widget(edit.page.id) }) {
                "Workspace page ID already exists"
            }
            val order = state.pageOrder.toMutableList()
            val position = edit.position ?: (order.indexOf(HomePageId.Home) + 1)
            require(position in 0..order.size) { "Invalid page position" }
            order.add(position, HomePageId.Widget(edit.page.id))
            state.copy(workspacePages = state.workspacePages + edit.page, pageOrder = order)
        }
        is OrganizationEdit.UpdateWorkspacePage -> {
            require(state.workspacePages.any { it.id == edit.page.id }) { "Workspace page no longer exists" }
            state.copy(workspacePages = state.workspacePages.map {
                if (it.id == edit.page.id) edit.page else it
            })
        }
        is OrganizationEdit.DeleteWorkspacePage -> {
            require(state.workspacePages.any { it.id == edit.id }) { "Workspace page no longer exists" }
            state.copy(workspacePages = state.workspacePages.filterNot { it.id == edit.id },
                pageOrder = state.pageOrder - HomePageId.Widget(edit.id),
                widgets = state.widgets.filterNot { it.pageId == WorkspacePageId.widget(edit.id) })
        }
        is OrganizationEdit.AddModulePage -> {
            require(state.modulePages.none { it.component == edit.page.component }) { "Module page already exists" }
            val order = state.pageOrder.toMutableList()
            val position = edit.position ?: (order.indexOf(HomePageId.Home) + 1)
            require(position in 0..order.size) { "Invalid page position" }
            order.add(position, HomePageId.Module(edit.page.component))
            state.copy(modulePages = state.modulePages + edit.page, pageOrder = order)
        }
        is OrganizationEdit.SetModulePageEnabled -> {
            require(state.modulePages.any { it.component == edit.component }) { "Module page no longer exists" }
            state.copy(modulePages = state.modulePages.map {
                if (it.component == edit.component) it.copy(enabled = edit.enabled) else it
            })
        }
        is OrganizationEdit.RemoveModulePage -> {
            require(state.modulePages.any { it.component == edit.component }) { "Module page no longer exists" }
            state.copy(modulePages = state.modulePages.filterNot { it.component == edit.component },
                pageOrder = state.pageOrder - HomePageId.Module(edit.component))
        }
        is OrganizationEdit.SetWorkspacePreferences -> state.copy(workspacePreferences = edit.preferences)
        is OrganizationEdit.SetWidgets -> state.copy(widgets = edit.widgets)
        is OrganizationEdit.SetListeningPreferences -> state.copy(listeningPreferences = edit.preferences)
    }

    private fun applyMembership(state: OrganizationSnapshot, pair: MembershipKey,
        override: MembershipOverride): OrganizationSnapshot {
        if (state.overrideFor(pair.categoryId, pair.appKey) == override) return state
        val overrides = state.overrides.toMutableMap()
        if (override == MembershipOverride.AUTO) overrides.remove(pair) else overrides[pair] = override
        val tokens = state.membershipTokens.toMutableMap()
        tokens[pair] = (tokens[pair] ?: 0L) + 1L
        return state.copy(overrides = overrides, membershipTokens = tokens)
    }

    companion object {
        /** Convenient transaction-safe ID for a custom category or folder. */
        fun newId(): String = java.util.UUID.randomUUID().toString()
    }
}

private fun decodeSnapshot(preferences: Preferences): OrganizationSnapshot {
    val raw = preferences[organizationKey]
    require(raw == null || raw.toByteArray(Charsets.UTF_8).size <= OrganizationLimits.MAX_DOCUMENT_BYTES) {
        "Organization document exceeds size limit"
    }
    val saved = if (raw == null) SavedOrganization() else
        json.decodeFromString(SavedOrganization.serializer(), raw)
    require(saved.version in 1..2) { "Unsupported organization version" }
    val settings = decodeMostUsedSettings(preferences[mostUsedSettingsKey])
    val snapshot = saved.public().copy(
        pins = settings.effectivePins().map { it.public() },
        pinsMigrated = settings.pinSchemaVersion == 2,
    )
    validate(snapshot)
    return snapshot
}

private fun encodeOrganization(snapshot: OrganizationSnapshot): String =
    json.encodeToString(SavedOrganization.serializer(), SavedOrganization.from(snapshot))

private fun validate(snapshot: OrganizationSnapshot) {
    require(snapshot.revision >= 0 && snapshot.categories.size in BuiltInCategories.ids.size..OrganizationLimits.MAX_CATEGORIES)
    require(snapshot.folders.size <= OrganizationLimits.MAX_FOLDERS)
    val categoryIds = snapshot.categories.map { it.id }
    require(categoryIds.distinct().size == categoryIds.size && "home" !in categoryIds &&
        BuiltInCategories.ids.all { it in categoryIds }) {
        "Invalid category IDs"
    }
    require(snapshot.categories.all { validId(it.id) && validName(it.name) &&
        (!it.automatic || it.id in setOf(BuiltInCategories.GAMES, BuiltInCategories.MEDIA,
            BuiltInCategories.SOCIAL, BuiltInCategories.PRODUCTIVITY)) &&
        it.builtIn == (it.id in BuiltInCategories.ids) }) { "Invalid category" }
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    require(snapshot.categories.indices.all { index ->
        (index + 1 until snapshot.categories.size).none {
            collator.compare(snapshot.categories[index].name, snapshot.categories[it].name) == 0
        }
    }) { "Duplicate category name" }
    val workspaceIds = snapshot.workspacePages.map { it.id }
    val moduleComponents = snapshot.modulePages.map { it.component }
    require(moduleComponents.size <= OrganizationLimits.MAX_MODULE_PAGES &&
        moduleComponents.distinct().size == moduleComponents.size &&
        snapshot.modulePages.all { page ->
            page.component.length in 3..512 && page.component.contains('/') &&
                page.providerId.length in 3..160 && validName(page.name) &&
                (page.signerKey.isEmpty() || (page.signerKey.length in 68..512 &&
                    page.signerKey.startsWith(page.component.substringBefore('/') + "|") &&
                    page.signerKey.substringAfter('|').split(',').all {
                        it.matches(Regex("[0-9a-f]{64}"))
                    }))
        }) { "Invalid module pages" }
    require(workspaceIds.size <= OrganizationLimits.MAX_WORKSPACE_PAGES &&
        workspaceIds.distinct().size == workspaceIds.size &&
        snapshot.workspacePages.all { validId(it.id) && validName(it.name) &&
            WorkspacePageId.widget(it.id) !in categoryIds }) { "Invalid workspace pages" }
    require(snapshot.workspacePages.indices.all { index ->
        (index + 1 until snapshot.workspacePages.size).none {
            collator.compare(snapshot.workspacePages[index].name, snapshot.workspacePages[it].name) == 0
        }
    }) { "Duplicate workspace page name" }
    require(snapshot.pageOrder.size == snapshot.categories.size + workspaceIds.size + moduleComponents.size + 1 &&
        snapshot.pageOrder.count { it == HomePageId.Home } == 1 &&
        snapshot.pageOrder.toSet() == categoryIds.map(HomePageId::Category).toSet() +
            workspaceIds.map(HomePageId::Widget).toSet() +
            moduleComponents.map(HomePageId::Module).toSet() + HomePageId.Home) {
        "Invalid page order"
    }
    require(snapshot.workspacePreferences.sectionOrder.size == WorkspaceSection.entries.size &&
        snapshot.workspacePreferences.sectionOrder.toSet() == WorkspaceSection.entries.toSet()) {
        "Invalid workspace section order"
    }
    require(snapshot.workspacePreferences.widgetAreaWidthPercent in
        OrganizationLimits.MIN_WIDGET_AREA_WIDTH_PERCENT..OrganizationLimits.MAX_WIDGET_AREA_WIDTH_PERCENT &&
        snapshot.workspacePreferences.widgetMarginDp in 0..OrganizationLimits.MAX_WIDGET_MARGIN_DP &&
        snapshot.workspacePreferences.widgetGapDp in 0..OrganizationLimits.MAX_WIDGET_GAP_DP &&
        snapshot.workspacePreferences.gridColumnCount in
            OrganizationLimits.MIN_GRID_COLUMNS..OrganizationLimits.MAX_GRID_COLUMNS &&
        snapshot.workspacePreferences.gridRowHeightDp in
            OrganizationLimits.MIN_GRID_ROW_HEIGHT_DP..OrganizationLimits.MAX_GRID_ROW_HEIGHT_DP) {
        "Invalid widget area settings"
    }
    val validWidgetPages = workspaceIds.mapTo(HashSet()) { WorkspacePageId.widget(it) }
    validWidgetPages.add(WorkspacePageId.HOME)
    require(snapshot.widgets.size <= OrganizationLimits.MAX_WIDGETS &&
        snapshot.widgets.map { it.appWidgetId }.distinct().size == snapshot.widgets.size &&
        snapshot.widgets.all { widget -> widget.appWidgetId > 0 &&
            widget.pageId in validWidgetPages &&
            widget.provider.length in 3..512 && widget.provider.contains('/') &&
            widget.provider.none { it.isISOControl() } &&
            widget.hostToken.length <= 128 && widget.hostToken.none { it.isISOControl() } &&
            widget.heightDp in OrganizationLimits.MIN_WIDGET_HEIGHT_DP..OrganizationLimits.MAX_WIDGET_HEIGHT_DP &&
            widget.span in 1..2 && widget.paddingDp in 0..OrganizationLimits.MAX_WIDGET_PADDING_DP &&
            widget.columnSpan in 0..OrganizationLimits.MAX_GRID_COLUMNS &&
            widget.rowSpan in 0..OrganizationLimits.MAX_WIDGET_GRID_ROW_SPAN &&
            widget.gridColumn in -1..(OrganizationLimits.MAX_GRID_COLUMNS - 1) &&
            widget.gridRow in -1..OrganizationLimits.MAX_WIDGET_GRID_ROW
        }) { "Invalid widget placement" }
    require(snapshot.listeningPreferences.apps.size <= OrganizationLimits.MAX_LISTENING_APPS &&
        snapshot.listeningPreferences.apps.distinct().size == snapshot.listeningPreferences.apps.size &&
        snapshot.listeningPreferences.apps.all(::validAppKey)) { "Invalid listening apps" }
    val folderIds = snapshot.folders.map { it.id }
    require(folderIds.distinct().size == folderIds.size && snapshot.folders.all { folder ->
        validId(folder.id) && validName(folder.name) &&
            folder.members.size <= OrganizationLimits.MAX_MEMBERS_PER_FOLDER &&
            folder.members.distinct().size == folder.members.size && folder.members.all(::validAppKey) &&
            folder.conditionIds.size <= OrganizationLimits.MAX_CONDITIONS_PER_FOLDER &&
            folder.conditionIds.distinct().size == folder.conditionIds.size &&
            folder.conditionIds.all(::validId)
    }) { "Invalid folder" }
    require(snapshot.folders.indices.all { index ->
        (index + 1 until snapshot.folders.size).none {
            collator.compare(snapshot.folders[index].name, snapshot.folders[it].name) == 0
        }
    }) { "Duplicate folder name" }
    require(snapshot.overrides.size <= OrganizationLimits.MAX_OVERRIDES &&
        snapshot.overrides.all { (pair, override) ->
            pair.categoryId in categoryIds && validAppKey(pair.appKey) && override != MembershipOverride.AUTO
        }) { "Invalid membership overrides" }
    require(snapshot.membershipTokens.size <= OrganizationLimits.MAX_OVERRIDES &&
        snapshot.membershipTokens.all { (pair, token) ->
            pair.categoryId in categoryIds && validAppKey(pair.appKey) && token > 0
        }) { "Invalid membership tokens" }
    require(snapshot.pins.size <= MostUsedSettingsStore.MAX_PINS &&
        snapshot.pins.distinct().size == snapshot.pins.size && snapshot.pins.all { pin ->
            when (pin) {
                is HomePinRef.App -> validAppKey(pin.key)
                is HomePinRef.Folder -> pin.id in folderIds &&
                    snapshot.folders.first { it.id == pin.id }.placement == FolderPlacement.MOST_USED
            }
        }) { "Invalid pin references" }
    require(snapshot.folders.filter { it.placement == FolderPlacement.MOST_USED }
        .all { HomePinRef.Folder(it.id) in snapshot.pins }) { "Missing folder pin" }
}

private fun validId(value: String): Boolean = value.isNotBlank() &&
    value.length <= OrganizationLimits.MAX_ID_LENGTH && value.none { it.isISOControl() }

private fun validName(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.isNotEmpty() && trimmed == value &&
        value.codePointCount(0, value.length) <= OrganizationLimits.MAX_NAME_CODE_POINTS &&
        value.none { it.isISOControl() }
}

private fun validAppKey(key: AppKey): Boolean = key.packageName.isNotBlank() &&
    key.packageName.length <= 255 && key.activityName.isNotBlank() &&
    key.activityName.length <= 512 && key.profileSerial >= 0

@Serializable private data class SavedKey(val packageName: String, val activityName: String,
    val profileSerial: Long) {
    fun public() = AppKey(packageName, activityName, profileSerial)
    companion object { fun from(key: AppKey) = SavedKey(key.packageName, key.activityName, key.profileSerial) }
}
@Serializable private data class SavedCategory(val id: String, val name: String, val enabled: Boolean,
    val showOnHome: Boolean, val automatic: Boolean, val builtIn: Boolean) {
    fun public() = CategoryDefinition(id, name, enabled, showOnHome, automatic, builtIn)
    companion object { fun from(category: CategoryDefinition) = SavedCategory(category.id,
        category.name, category.enabled, category.showOnHome, category.automatic, category.builtIn) }
}
@Serializable private data class SavedFolder(val id: String, val name: String,
    val members: List<SavedKey>, val placement: FolderPlacement,
    val visibilityMode: FolderVisibilityMode, val matchMode: FolderMatchMode,
    val unknownFallback: UnknownFallback, val conditionIds: List<String>,
    val iconId: String = "folder") {
    fun public() = FolderDefinition(id, name, members.map { it.public() }, placement,
        visibilityMode, matchMode, unknownFallback, conditionIds, iconId)
    companion object { fun from(folder: FolderDefinition) = SavedFolder(folder.id, folder.name,
        folder.members.map(SavedKey::from), folder.placement, folder.visibilityMode,
        folder.matchMode, folder.unknownFallback, folder.conditionIds, folder.iconId) }
}
@Serializable private data class SavedMembership(val categoryId: String, val app: SavedKey,
    val override: MembershipOverride, val token: Long) {
    fun pair() = MembershipKey(categoryId, app.public())
}
@Serializable private data class SavedWorkspacePage(val id: String, val name: String,
    val enabled: Boolean = true) {
    fun public() = WorkspacePage(id, name, enabled)
    companion object { fun from(page: WorkspacePage) = SavedWorkspacePage(page.id, page.name, page.enabled) }
}
@Serializable private data class SavedModuleHomePage(
    val component: String, val providerId: String, val name: String, val enabled: Boolean,
    val signerKey: String = "",
) {
    fun public() = ModuleHomePage(component, providerId, name, enabled, signerKey)
    companion object {
        fun from(page: ModuleHomePage) = SavedModuleHomePage(
            page.component, page.providerId, page.name, page.enabled, page.signerKey,
        )
    }
}
@Serializable private data class SavedWorkspacePreferences(
    val showClock: Boolean = true,
    val showDate: Boolean = true,
    val showBattery: Boolean = false,
    val sectionOrder: List<WorkspaceSection> = WorkspaceSection.entries.toList(),
    val widgetAreaWidthPercent: Int = 100,
    val widgetMarginDp: Int = 16,
    val widgetGapDp: Int = 12,
    val gridColumnCount: Int = 4,
    val gridRowHeightDp: Int = 96,
) {
    fun public() = WorkspacePreferences(showClock, showDate, showBattery, sectionOrder,
        widgetAreaWidthPercent, widgetMarginDp, widgetGapDp, gridColumnCount, gridRowHeightDp)
    companion object { fun from(preferences: WorkspacePreferences) = SavedWorkspacePreferences(
        preferences.showClock, preferences.showDate, preferences.showBattery, preferences.sectionOrder,
        preferences.widgetAreaWidthPercent, preferences.widgetMarginDp, preferences.widgetGapDp,
        preferences.gridColumnCount, preferences.gridRowHeightDp) }
}
@Serializable private data class SavedWidget(val appWidgetId: Int, val pageId: String,
    val provider: String, val heightDp: Int, val hostToken: String = "",
    val span: Int = 2, val paddingDp: Int = 0,
    val columnSpan: Int = 0, val rowSpan: Int = 0,
    val gridColumn: Int = -1, val gridRow: Int = -1) {
    fun public() = WorkspaceWidget(appWidgetId, pageId, provider, heightDp, hostToken, span,
        paddingDp, columnSpan, rowSpan, gridColumn, gridRow)
    companion object { fun from(widget: WorkspaceWidget) = SavedWidget(widget.appWidgetId,
        widget.pageId, widget.provider, widget.heightDp, widget.hostToken,
        widget.span, widget.paddingDp, widget.columnSpan, widget.rowSpan,
        widget.gridColumn, widget.gridRow) }
}
@Serializable private data class SavedListeningPreferences(
    val enabled: Boolean = false,
    val kind: HeadsetKind = HeadsetKind.ANY,
    val apps: List<SavedKey> = emptyList(),
) {
    fun public() = ListeningPreferences(enabled, kind, apps.map { it.public() })
    companion object { fun from(preferences: ListeningPreferences) = SavedListeningPreferences(
        preferences.enabled, preferences.kind, preferences.apps.map(SavedKey::from)) }
}
@Serializable private data class SavedOrganization(
    /** Legacy JSON omitted this field when its default was version 1. */
    val version: Int = 1,
    val revision: Long = 0,
    val categoriesEnabled: Boolean = false,
    val categories: List<SavedCategory> = BuiltInCategories.defaults.map(SavedCategory::from),
    val folders: List<SavedFolder> = emptyList(),
    val pageOrder: List<String> = listOf("home") + BuiltInCategories.ids,
    val memberships: List<SavedMembership> = emptyList(),
    val workspacePages: List<SavedWorkspacePage> = emptyList(),
    val modulePages: List<SavedModuleHomePage> = emptyList(),
    val workspacePreferences: SavedWorkspacePreferences = SavedWorkspacePreferences(),
    val widgets: List<SavedWidget> = emptyList(),
    val listeningPreferences: SavedListeningPreferences = SavedListeningPreferences(),
) {
    fun public(): OrganizationSnapshot {
        val pairs = memberships.map { it.pair() }
        require(pairs.distinct().size == pairs.size) { "Duplicate membership pair" }
        val categoryIds = categories.mapTo(HashSet()) { it.id }
        val widgetIds = workspacePages.mapTo(HashSet()) { WorkspacePageId.widget(it.id) }
        val moduleIds = modulePages.mapTo(HashSet()) { "module:${it.component}" }
        return OrganizationSnapshot(revision = revision, categoriesEnabled = categoriesEnabled,
            categories = categories.map { it.public() }, folders = folders.map { it.public() },
            pageOrder = pageOrder.map { key -> when {
                key == WorkspacePageId.HOME -> HomePageId.Home
                key in categoryIds || version == 1 -> HomePageId.Category(key)
                key in widgetIds -> HomePageId.Widget(key.removePrefix(WorkspacePageId.WIDGET_PREFIX))
                key in moduleIds -> HomePageId.Module(key.removePrefix("module:"))
                else -> HomePageId.Category(key)
            } },
            overrides = memberships.filter { it.override != MembershipOverride.AUTO }
                .associate { it.pair() to it.override },
            membershipTokens = memberships.associate { it.pair() to it.token },
            workspacePages = workspacePages.map { it.public() },
            modulePages = modulePages.map { it.public() },
            workspacePreferences = workspacePreferences.public(),
            widgets = widgets.map { it.public() },
            listeningPreferences = listeningPreferences.public())
    }
    companion object {
        fun from(snapshot: OrganizationSnapshot): SavedOrganization {
            val pairs = (snapshot.overrides.keys + snapshot.membershipTokens.keys).distinct()
            return SavedOrganization(version = 2, revision = snapshot.revision,
                categoriesEnabled = snapshot.categoriesEnabled,
                categories = snapshot.categories.map(SavedCategory::from),
                folders = snapshot.folders.map(SavedFolder::from),
                pageOrder = snapshot.pageOrder.map { when (it) {
                    HomePageId.Home -> "home"
                    is HomePageId.Category -> it.id
                    is HomePageId.Widget -> WorkspacePageId.widget(it.id)
                    is HomePageId.Module -> "module:${it.component}"
                } },
                memberships = pairs.map { pair -> SavedMembership(pair.categoryId,
                    SavedKey.from(pair.appKey), snapshot.overrides[pair] ?: MembershipOverride.AUTO,
                    snapshot.membershipTokens[pair] ?: 0L) },
                workspacePages = snapshot.workspacePages.map(SavedWorkspacePage::from),
                modulePages = snapshot.modulePages.map(SavedModuleHomePage::from),
                workspacePreferences = SavedWorkspacePreferences.from(snapshot.workspacePreferences),
                widgets = snapshot.widgets.map(SavedWidget::from),
                listeningPreferences = SavedListeningPreferences.from(snapshot.listeningPreferences))
        }
    }
}
