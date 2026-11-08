package io.github.alexanderwilhelmsenberg.modular.organization

import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppCustomizations
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import java.text.Collator
import java.util.Locale

data class ResolvedFolder(
    val definition: FolderDefinition,
    /** Exact visible, currently usable members in saved order. */
    val apps: List<LaunchableApp>,
    /** Stored references remain editable when an app is unavailable or hidden. */
    val unavailableMembers: List<AppKey>,
)

data class OrganizationProjection(
    /** All non-hidden catalog rows, once each; unavailable entries stay disabled in UI. */
    val globalSearchApps: List<LaunchableApp>,
    /** Direct Home app rows, after category suppression. */
    val homeApps: List<LaunchableApp>,
    val categoryPages: Map<String, List<LaunchableApp>>,
    /** Eligible app-list shortcuts, sorted by folder display name. */
    val appListFolders: List<ResolvedFolder>,
    /** Eligible Most Used folder shortcuts in saved pin order. */
    val mostUsedFolders: List<ResolvedFolder>,
    /** Direct pinned apps after suppression, in saved pin order. */
    val visiblePinnedApps: List<LaunchableApp>,
    /** Typed pin order after both app and folder visibility; suggestions follow it. */
    val visiblePinRefs: List<HomePinRef>,
    val suppressedPinnedApps: List<AppKey>,
    val usableHomeKeys: Set<AppKey>,
) {
    val homeKeys: Set<AppKey> get() = homeApps.mapTo(HashSet()) { it.key }
    fun filterDirectStarred(apps: List<LaunchableApp>): List<LaunchableApp> =
        apps.filter { it.key in usableHomeKeys }
}

object OrganizationProjector {
    /**
     * The caller supplies live smart-folder eligibility. A missing smart signal
     * follows the folder's persisted Unknown fallback until a coordinator resolves it.
     */
    fun project(
        catalog: AppCatalog,
        customizations: AppCustomizations,
        snapshot: OrganizationSnapshot,
        smartFolderVisibility: Map<String, Boolean> = emptyMap(),
    ): OrganizationProjection {
        val usable = catalog.apps.filter { app ->
            app.enabled && catalog.profiles.none { profile ->
                profile.serialNumber == app.key.profileSerial && profile.quietModeEnabled
            }
        }
        val visible = sorted(customizations.visibleApps(catalog.apps))
        val usableKeys = usable.mapTo(HashSet()) { it.key }
        val byKey = visible.filter { it.key in usableKeys }.associateBy { it.key }
        val members = snapshot.categories.associate { category ->
            category.id to visible.filter { CategoryClassifier.membership(snapshot, category, it) }
        }
        val suppressing = if (!snapshot.categoriesEnabled) emptySet() else
            snapshot.categories.asSequence().filter { it.enabled && !it.showOnHome }
                .flatMap { members.getValue(it.id).asSequence() }.map { it.key }.toSet()
        val home = visible.filterNot { it.key in suppressing }
        val homeKeys = home.mapTo(HashSet()) { it.key }
        val visibleFolders = snapshot.folders.filter { folder ->
            when (folder.visibilityMode) {
                FolderVisibilityMode.ALWAYS -> true
                FolderVisibilityMode.HIDDEN -> false
                FolderVisibilityMode.SMART -> smartFolderVisibility[folder.id]
                    ?: (folder.unknownFallback == UnknownFallback.SHOW)
            }
        }.map { folder ->
            ResolvedFolder(folder, folder.members.mapNotNull(byKey::get),
                folder.members.filterNot(byKey::containsKey))
        }
        val folderById = visibleFolders.associateBy { it.definition.id }
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        val appListFolders = visibleFolders.filter { it.definition.placement == FolderPlacement.APP_LIST }
            .sortedWith { left, right ->
                collator.compare(left.definition.name, right.definition.name).takeIf { it != 0 }
                    ?: left.definition.id.compareTo(right.definition.id)
            }
        val visiblePins = snapshot.pins.filter { pin -> when (pin) {
            is HomePinRef.App -> pin.key in homeKeys && pin.key in usableKeys
            is HomePinRef.Folder -> pin.id in folderById &&
                folderById.getValue(pin.id).definition.placement == FolderPlacement.MOST_USED
        } }
        return OrganizationProjection(
            globalSearchApps = visible,
            homeApps = home,
            categoryPages = snapshot.categories.filter { snapshot.categoriesEnabled && it.enabled }
                .associate { it.id to members.getValue(it.id) },
            appListFolders = appListFolders,
            mostUsedFolders = visiblePins.mapNotNull { pin ->
                if (pin is HomePinRef.Folder) folderById[pin.id] else null
            },
            visiblePinnedApps = visiblePins.mapNotNull { pin ->
                if (pin is HomePinRef.App) byKey[pin.key] else null
            },
            visiblePinRefs = visiblePins,
            suppressedPinnedApps = snapshot.pins.mapNotNull { pin ->
                if (pin is HomePinRef.App && pin.key in suppressing) pin.key else null
            },
            usableHomeKeys = homeKeys.intersect(usableKeys),
        )
    }

    private fun sorted(apps: List<LaunchableApp>): List<LaunchableApp> {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        return apps.sortedWith { left, right ->
            collator.compare(left.label, right.label).takeIf { it != 0 }
                ?: left.label.compareTo(right.label).takeIf { it != 0 }
                ?: left.key.stableId.compareTo(right.key.stableId)
        }
    }
}
