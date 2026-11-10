package io.github.alexanderwilhelmsenberg.modular.organization

import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.workspace.ListeningPreferences
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePage
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePreferences
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget

/** IDs are stable storage keys. Names are presentation only. */
object BuiltInCategories {
    const val GAMES = "games"
    const val MEDIA = "media"
    const val SOCIAL = "social"
    const val PRODUCTIVITY = "productivity"
    const val WORK = "work"
    const val FINANCE = "finance"
    val ids = listOf(GAMES, MEDIA, SOCIAL, PRODUCTIVITY, WORK, FINANCE)
    val defaults = listOf(
        CategoryDefinition(GAMES, "Games", automatic = true),
        CategoryDefinition(MEDIA, "Media", automatic = true),
        CategoryDefinition(SOCIAL, "Social", automatic = true),
        CategoryDefinition(PRODUCTIVITY, "Productivity", automatic = true),
        CategoryDefinition(WORK, "Work"),
        CategoryDefinition(FINANCE, "Finance"),
    )
}

enum class MembershipOverride { AUTO, INCLUDE, EXCLUDE }
enum class MembershipProvenance { MANUAL, ANDROID_CATEGORY, NONE }
enum class FolderPlacement { APP_LIST, MOST_USED }
enum class FolderVisibilityMode { ALWAYS, SMART, HIDDEN }
enum class FolderMatchMode { ANY, ALL }
enum class UnknownFallback { SHOW, HIDE }

data class CategoryDefinition(
    val id: String,
    val name: String,
    val enabled: Boolean = false,
    val showOnHome: Boolean = true,
    val automatic: Boolean = false,
    val builtIn: Boolean = id in BuiltInCategories.ids,
)

data class FolderDefinition(
    val id: String,
    val name: String,
    val members: List<AppKey> = emptyList(),
    val placement: FolderPlacement = FolderPlacement.APP_LIST,
    val visibilityMode: FolderVisibilityMode = FolderVisibilityMode.ALWAYS,
    val matchMode: FolderMatchMode = FolderMatchMode.ANY,
    val unknownFallback: UnknownFallback = UnknownFallback.SHOW,
    /** Opaque references to sensitive, no-backup rule records. */
    val conditionIds: List<String> = emptyList(),
    /** Stable bundled icon ID. Unknown future IDs are retained in storage. */
    val iconId: String = "folder",
)

sealed interface HomePageId {
    data object Home : HomePageId
    data class Category(val id: String) : HomePageId
    data class Widget(val id: String) : HomePageId
    data class Module(val component: String) : HomePageId
}

/** One user-added native page per installed module service. */
data class ModuleHomePage(
    val component: String,
    val providerId: String,
    val name: String,
    val enabled: Boolean = true,
    /** Installed APK signer identity at the user's Add to Home decision. */
    val signerKey: String = "",
)

sealed interface HomePinRef {
    data class App(val key: AppKey) : HomePinRef
    data class Folder(val id: String) : HomePinRef
}

data class MembershipKey(val categoryId: String, val appKey: AppKey)

data class OrganizationSnapshot(
    val revision: Long = 0,
    val categoriesEnabled: Boolean = false,
    val categories: List<CategoryDefinition> = BuiltInCategories.defaults,
    val folders: List<FolderDefinition> = emptyList(),
    val pageOrder: List<HomePageId> = listOf(HomePageId.Home) +
        BuiltInCategories.ids.map(HomePageId::Category),
    /** Only explicit Include/Exclude entries are persisted. Missing means Auto. */
    val overrides: Map<MembershipKey, MembershipOverride> = emptyMap(),
    /** Monotonic tokens per pair, used to guard sorter Undo. */
    val membershipTokens: Map<MembershipKey, Long> = emptyMap(),
    val pins: List<HomePinRef> = emptyList(),
    val pinsMigrated: Boolean = false,
    val workspacePages: List<WorkspacePage> = emptyList(),
    val modulePages: List<ModuleHomePage> = emptyList(),
    val workspacePreferences: WorkspacePreferences = WorkspacePreferences(),
    val widgets: List<WorkspaceWidget> = emptyList(),
    val listeningPreferences: ListeningPreferences = ListeningPreferences(),
) {
    fun overrideFor(categoryId: String, appKey: AppKey): MembershipOverride =
        overrides[MembershipKey(categoryId, appKey)] ?: MembershipOverride.AUTO

    fun visiblePageOrder(): List<HomePageId> {
        val enabled = categories.filter { it.enabled }.mapTo(HashSet()) { it.id }
        val enabledWidgets = workspacePages.filter { it.enabled }.mapTo(HashSet()) { it.id }
        val enabledModules = modulePages.filter { it.enabled }.mapTo(HashSet()) { it.component }
        return pageOrder.filter { page -> when (page) {
            HomePageId.Home -> true
            is HomePageId.Category -> categoriesEnabled && page.id in enabled
            is HomePageId.Widget -> page.id in enabledWidgets
            is HomePageId.Module -> page.component in enabledModules
        } }
    }
}

/** An entire picker Done action is one transaction; callers stage edits until Done. */
data class OrganizationBatch(
    val edits: List<OrganizationEdit>,
    val expectedRevision: Long? = null,
)

sealed interface OrganizationEdit {
    data class SetCategoriesEnabled(val enabled: Boolean) : OrganizationEdit
    data class CreateCategory(val category: CategoryDefinition, val position: Int? = null) : OrganizationEdit
    data class UpdateCategory(val category: CategoryDefinition) : OrganizationEdit
    data class DeleteCategory(val id: String) : OrganizationEdit
    data class SetPageOrder(val order: List<HomePageId>) : OrganizationEdit
    data class SetMembership(val categoryId: String, val appKey: AppKey,
        val override: MembershipOverride) : OrganizationEdit
    data class ResetCategoryOverrides(val categoryId: String) : OrganizationEdit
    data class CreateFolder(val folder: FolderDefinition) : OrganizationEdit
    data class UpdateFolder(val folder: FolderDefinition) : OrganizationEdit
    data class DeleteFolder(val id: String) : OrganizationEdit
    data class SetPins(val order: List<HomePinRef>) : OrganizationEdit
    data class CreateWorkspacePage(val page: WorkspacePage, val position: Int? = null) : OrganizationEdit
    data class UpdateWorkspacePage(val page: WorkspacePage) : OrganizationEdit
    data class DeleteWorkspacePage(val id: String) : OrganizationEdit
    data class AddModulePage(val page: ModuleHomePage, val position: Int? = null) : OrganizationEdit
    data class SetModulePageEnabled(val component: String, val enabled: Boolean) : OrganizationEdit
    data class RemoveModulePage(val component: String) : OrganizationEdit
    data class SetWorkspacePreferences(val preferences: WorkspacePreferences) : OrganizationEdit
    data class SetWidgets(val widgets: List<WorkspaceWidget>) : OrganizationEdit
    data class SetListeningPreferences(val preferences: ListeningPreferences) : OrganizationEdit
}

data class MembershipChange(
    val categoryId: String,
    val appKey: AppKey,
    val previous: MembershipOverride,
    val current: MembershipOverride,
    /** Pair-local token; unrelated edits do not invalidate Undo. */
    val token: Long,
)

class OrganizationConflictException(message: String) : IllegalStateException(message)
class OrganizationDestinationMissingException(message: String) : IllegalStateException(message)
