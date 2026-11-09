package io.github.alexanderwilhelmsenberg.modular.ui

import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.organization.FolderDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.HomePinRef

/** Core app pins only rank Core suggestions; folder ordering is always retained. */
internal fun drawerPinOrder(pins: List<HomePinRef>, coreMostUsedEnabled: Boolean): List<HomePinRef> =
    if (coreMostUsedEnabled) pins else pins.filterIsInstance<HomePinRef.Folder>()

/** Saved app pins lead suggested Most Used apps. Folder pins are handled separately. */
internal fun featuredHomeEntries(
    apps: List<LaunchableApp>?,
    pinOrder: List<HomePinRef>,
    showStatus: Boolean,
): List<HomeListEntry> = buildList {
    if (apps == null) return@buildList
    add(HomeListEntry.Header("★"))
    if (apps.isEmpty()) {
        add(HomeListEntry.FeaturedEmpty)
        return@buildList
    }
    val appByKey = apps.associateBy { it.key }
    val orderedApps = (pinOrder.filterIsInstance<HomePinRef.App>().mapNotNull { appByKey[it.key] } +
        apps).distinctBy { it.key }
    orderedApps.forEach { add(HomeListEntry.App(it, featured = true)) }
    if (showStatus) add(HomeListEntry.FeaturedStatus)
}

/** One drawer sequence, with saved folder pins before alphabetical app-list folders. */
internal fun mergedDrawerFolders(
    pinnedFolders: List<FolderDefinition>,
    appListFolders: List<FolderDefinition>,
    pinOrder: List<HomePinRef>,
): List<FolderDefinition> {
    val byId = (pinnedFolders + appListFolders).associateBy { it.id }
    return (pinOrder.filterIsInstance<HomePinRef.Folder>().mapNotNull { byId[it.id] } +
        pinnedFolders + appListFolders).distinctBy { it.id }
}

/** Most Used, one Folders section, then the app alphabet. Search skips this sequence. */
internal fun drawerSectionEntries(
    featuredApps: List<LaunchableApp>?,
    folders: List<FolderDefinition>,
    pinOrder: List<HomePinRef>,
    showStatus: Boolean,
    sections: List<HomeSection>,
    unsectionedApps: List<LaunchableApp> = emptyList(),
): List<HomeListEntry> = buildList {
    addAll(featuredHomeEntries(featuredApps, pinOrder, showStatus))
    if (folders.isNotEmpty()) {
        add(HomeListEntry.Header("Folders"))
        folders.forEach { add(HomeListEntry.Folder(it)) }
    }
    sections.forEach { section ->
        add(HomeListEntry.Header(section.label))
        section.apps.forEach { add(HomeListEntry.App(it)) }
    }
    unsectionedApps.forEach { add(HomeListEntry.App(it)) }
}
