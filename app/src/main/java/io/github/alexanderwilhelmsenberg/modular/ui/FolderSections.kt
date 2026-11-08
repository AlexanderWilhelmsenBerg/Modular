package io.github.alexanderwilhelmsenberg.modular.ui

import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.organization.FolderDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.HomePinRef

/** Saved mixed pins remain compatible; folders are displayed after every featured app. */
internal fun featuredHomeEntries(
    apps: List<LaunchableApp>?,
    folders: List<FolderDefinition>,
    pinOrder: List<HomePinRef>,
    showStatus: Boolean,
): List<HomeListEntry> = buildList {
    if (apps == null && folders.isEmpty()) return@buildList
    add(HomeListEntry.Header("★"))
    if (apps.isNullOrEmpty() && folders.isEmpty()) {
        add(HomeListEntry.FeaturedEmpty)
        return@buildList
    }
    val appByKey = apps.orEmpty().associateBy { it.key }
    val orderedApps = (pinOrder.filterIsInstance<HomePinRef.App>().mapNotNull { appByKey[it.key] } +
        apps.orEmpty()).distinctBy { it.key }
    orderedApps.forEach { add(HomeListEntry.App(it, featured = true)) }
    if (showStatus) add(HomeListEntry.FeaturedStatus)
    if (folders.isNotEmpty()) {
        add(HomeListEntry.Header("Folders", "featured-folders"))
        val byId = folders.associateBy { it.id }
        (pinOrder.filterIsInstance<HomePinRef.Folder>().mapNotNull { byId[it.id] } + folders)
            .distinctBy { it.id }.forEach { add(HomeListEntry.Folder(it)) }
    }
}
