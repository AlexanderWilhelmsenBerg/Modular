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
private val json = Json { ignoreUnknownKeys = false }

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
                if (next.pins.any { it is HomePinRef.Folder && it !in before.pins }) {
                    require(settings.enabled) { "Turn on Most Used before placing a folder there" }
                }
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
    require(saved.version == 1) { "Unsupported organization version" }
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
    require(snapshot.pageOrder.size == snapshot.categories.size + 1 &&
        snapshot.pageOrder.count { it == HomePageId.Home } == 1 &&
        snapshot.pageOrder.toSet() == categoryIds.map(HomePageId::Category).toSet() + HomePageId.Home) {
        "Invalid page order"
    }
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
    val unknownFallback: UnknownFallback, val conditionIds: List<String>) {
    fun public() = FolderDefinition(id, name, members.map { it.public() }, placement,
        visibilityMode, matchMode, unknownFallback, conditionIds)
    companion object { fun from(folder: FolderDefinition) = SavedFolder(folder.id, folder.name,
        folder.members.map(SavedKey::from), folder.placement, folder.visibilityMode,
        folder.matchMode, folder.unknownFallback, folder.conditionIds) }
}
@Serializable private data class SavedMembership(val categoryId: String, val app: SavedKey,
    val override: MembershipOverride, val token: Long) {
    fun pair() = MembershipKey(categoryId, app.public())
}
@Serializable private data class SavedOrganization(
    val version: Int = 1,
    val revision: Long = 0,
    val categoriesEnabled: Boolean = false,
    val categories: List<SavedCategory> = BuiltInCategories.defaults.map(SavedCategory::from),
    val folders: List<SavedFolder> = emptyList(),
    val pageOrder: List<String> = listOf("home") + BuiltInCategories.ids,
    val memberships: List<SavedMembership> = emptyList(),
) {
    fun public(): OrganizationSnapshot {
        val pairs = memberships.map { it.pair() }
        require(pairs.distinct().size == pairs.size) { "Duplicate membership pair" }
        return OrganizationSnapshot(revision, categoriesEnabled, categories.map { it.public() },
            folders.map { it.public() }, pageOrder.map { if (it == "home") HomePageId.Home
                else HomePageId.Category(it) },
            memberships.filter { it.override != MembershipOverride.AUTO }.associate { it.pair() to it.override },
            memberships.associate { it.pair() to it.token })
    }
    companion object {
        fun from(snapshot: OrganizationSnapshot): SavedOrganization {
            val pairs = (snapshot.overrides.keys + snapshot.membershipTokens.keys).distinct()
            return SavedOrganization(revision = snapshot.revision,
                categoriesEnabled = snapshot.categoriesEnabled,
                categories = snapshot.categories.map(SavedCategory::from),
                folders = snapshot.folders.map(SavedFolder::from),
                pageOrder = snapshot.pageOrder.map { when (it) {
                    HomePageId.Home -> "home"
                    is HomePageId.Category -> it.id
                } },
                memberships = pairs.map { pair -> SavedMembership(pair.categoryId,
                    SavedKey.from(pair.appKey), snapshot.overrides[pair] ?: MembershipOverride.AUTO,
                    snapshot.membershipTokens[pair] ?: 0L) })
        }
    }
}
