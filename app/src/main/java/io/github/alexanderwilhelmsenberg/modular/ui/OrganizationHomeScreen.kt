package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.apps.*
import io.github.alexanderwilhelmsenberg.modular.organization.*
import kotlinx.coroutines.launch

/** Organization supplies projections and transient UI; Home retains its list/search/rail implementation. */
@Composable
fun OrganizationHomeScreen(
    catalog: AppCatalog,
    runtime: OrganizationRuntime,
    snapshot: OrganizationSnapshot,
    mostUsedEnabled: Boolean,
    hiddenKeys: Set<AppKey>,
    sorterController: MegaSorterController?,
    onExitSorter: () -> Unit,
    onStartSorter: (String?, String?) -> Unit,
    onLaunch: (LaunchableApp) -> Unit,
    onRefresh: () -> Unit,
    onMakeDefault: () -> Unit,
    onOpenSettings: () -> Unit,
    showMakeDefault: Boolean = true,
    listState: LazyListState = rememberLazyListState(),
    orderedApps: List<LaunchableApp>? = null,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    showAppIcons: Boolean = true,
    wallpaperMode: Boolean = false,
    wallpaperDimmingPercent: Int = 35,
    featuredApps: List<LaunchableApp>? = null,
    pinnedAppKeys: Set<AppKey> = emptySet(),
    featuredMessage: String? = null,
    onOpenFeaturedSettings: (() -> Unit)? = null,
    featuredNeedsPermission: Boolean = false,
    onOpenFeaturedManagement: (() -> Unit)? = null,
    onPinApp: ((LaunchableApp, Boolean) -> Unit)? = null,
    onAppInfo: ((LaunchableApp) -> Unit)? = null,
    onUninstallApp: ((LaunchableApp) -> Unit)? = null,
    onRenameApp: ((LaunchableApp) -> Unit)? = null,
    onHideApp: ((LaunchableApp) -> Unit)? = null,
    alphabeticalSections: Boolean = true,
    homeResetToken: Int = 0,
) {
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var sorterHeaderHeight by remember { mutableIntStateOf(0) }
    val statuses by runtime.signals.statuses.collectAsState()
    var retainedVisibility by remember { mutableStateOf(statuses.mapValues { it.value.eligible }) }
    var folderPressed by remember { mutableStateOf(false) }
    var openFolderId by remember { mutableStateOf<String?>(null) }
    var editingFolderId by remember { mutableStateOf<String?>(null) }
    var organizeApp by remember { mutableStateOf<LaunchableApp?>(null) }
    var folderAction by remember { mutableStateOf<Pair<String, LaunchableApp>?>(null) }
    var editingSearch by remember { mutableStateOf(false) }
    var setupSorter by remember { mutableStateOf(false) }
    var categoryMembers by remember { mutableStateOf<String?>(null) }
    var pageName by rememberSaveable { mutableStateOf("home") }
    var error by remember { mutableStateOf<String?>(null) }
    val scrollingPages = remember { mutableStateMapOf<HomePageId, Boolean>() }
    var pagerScrolling by remember { mutableStateOf(false) }
    val pageScrolling = pagerScrolling || scrollingPages.values.any { it }
    val folderBounds = remember { mutableStateMapOf<String, androidx.compose.ui.unit.IntRect>() }
    LaunchedEffect(statuses, folderPressed, openFolderId, pageScrolling) {
        if (!folderPressed && openFolderId == null && !pageScrolling)
            retainedVisibility = statuses.mapValues { it.value.eligible }
    }
    val projection = remember(catalog, snapshot, retainedVisibility) {
        OrganizationProjector.project(catalog, AppCustomizations(), snapshot, retainedVisibility)
    }
    val sorter = sorterController?.state?.collectAsState()?.value
    LaunchedEffect(catalog, hiddenKeys, sorterController) {
        val unavailable = catalog.apps.filter { app -> !app.enabled || catalog.profiles.any {
            it.serialNumber == app.key.profileSerial && it.quietModeEnabled
        } }.mapTo(HashSet()) { it.key }
        sorterController?.updateCatalog(projection.globalSearchApps, hiddenKeys, unavailable)
    }
    LaunchedEffect(sorter?.active) { if (sorter != null && !sorter.active) onExitSorter() }
    LaunchedEffect(homeResetToken) {
        openFolderId = null; editingFolderId = null; organizeApp = null; folderAction = null
    }
    BackHandler(enabled = searchQuery.isNotBlank() || sorterController != null) {
        if (searchQuery.isNotBlank()) onSearchQueryChange("") else onExitSorter()
    }
    val initialPage = if (pageName == "home") HomePageId.Home else HomePageId.Category(pageName)
    Box(Modifier.fillMaxSize()) {
        LauncherPages(snapshot = if (sorter != null) snapshot.copy(categoriesEnabled = false) else snapshot,
            gesturesEnabled = !editingSearch && searchQuery.isBlank() && sorter == null &&
                openFolderId == null && organizeApp == null && folderAction == null &&
                categoryMembers == null && editingFolderId == null && !setupSorter,
            homeResetToken = homeResetToken, initialPageId = initialPage,
            onScrollingChange = { pagerScrolling = it },
            onPageChanged = { pageName = if (it is HomePageId.Category) it.id else "home" },
            renderPage = { page ->
                val category = (page as? HomePageId.Category)?.let { id -> snapshot.categories.firstOrNull { it.id == id.id } }
                val apps = when {
                    sorter != null -> sorter.rows.map { it.app.copy(enabled = it.available && it.app.enabled) }
                    category != null -> projection.categoryPages[category.id].orEmpty()
                    else -> projection.homeApps
                }
                val pageList = if (page == HomePageId.Home) listState else rememberLazyListState()
                LaunchedEffect(page, pageList.isScrollInProgress) {
                    scrollingPages[page] = pageList.isScrollInProgress
                }
                DisposableEffect(page) { onDispose { scrollingPages.remove(page) } }
                val featured = if (category == null && sorter == null) featuredApps?.let(projection::filterDirectStarred) else null
                HomeScreen(
                    catalog = catalog.copy(apps = apps),
                    searchApps = if (sorter != null) apps else projection.globalSearchApps,
                    orderedApps = when {
                        sorter != null -> apps
                        category != null -> null
                        else -> orderedApps?.filter { it.key in projection.homeKeys }
                    },
                    pageTitle = category?.name,
                    listTopInset = if (sorter != null) with(density) { sorterHeaderHeight.toDp() } else 0.dp,
                    listState = pageList,
                    onLaunch = onLaunch, onRefresh = onRefresh, onMakeDefault = onMakeDefault,
                    onOpenSettings = { if (sorterController != null) onExitSorter(); onOpenSettings() },
                    showMakeDefault = showMakeDefault && category == null,
                    searchQuery = searchQuery, onSearchQueryChange = onSearchQueryChange,
                    showAppIcons = showAppIcons, wallpaperMode = wallpaperMode,
                    wallpaperDimmingPercent = wallpaperDimmingPercent,
                    featuredApps = featured,
                    pinnedAppKeys = pinnedAppKeys, featuredMessage = featuredMessage,
                    onOpenFeaturedSettings = onOpenFeaturedSettings,
                    featuredNeedsPermission = featuredNeedsPermission,
                    onOpenFeaturedManagement = onOpenFeaturedManagement,
                    onPinApp = onPinApp, onAppInfo = onAppInfo,
                    onUninstallApp = onUninstallApp, onRenameApp = onRenameApp, onHideApp = onHideApp,
                    onOrganizeApp = { organizeApp = it },
                    alphabeticalSections = if (category != null || sorter != null) true else alphabeticalSections,
                    homeResetToken = homeResetToken,
                    folders = if (category == null && sorter == null) projection.appListFolders.map { it.definition } else emptyList(),
                    featuredFolders = if (category == null && sorter == null && mostUsedEnabled)
                        projection.mostUsedFolders.map { it.definition } else emptyList(),
                    featuredPinOrder = if (mostUsedEnabled) projection.visiblePinRefs else emptyList(),
                    onOpenFolder = { openFolderId = it.id }, onEditFolder = { editingFolderId = it.id },
                    onFolderPress = { folderPressed = it }, onEditingChange = { editingSearch = it },
                    onFolderBounds = { id, bounds -> folderBounds[id] = bounds },
                    onAddCategoryApps = if (category != null) ({ categoryMembers = category.id }) else null,
                    onStartCategorySorter = if (category != null) ({ setupSorter = true }) else null,
                    appRowContent = if (sorter != null && sorterController != null) { app, rowContent ->
                        sorter.rows.firstOrNull { it.app.key == app.key }?.let { row ->
                            MegaSorterRow(row, sorter, onAssign = { sorterController.assign(app.key, it) }, content = rowContent)
                        }
                    } else null,
                )
            })
        if (sorter != null && sorterController != null) MegaSorterOverlay(sorter,
            onExit = onExitSorter, onUndo = sorterController::undo,
            onHeaderHeightChanged = { sorterHeaderHeight = it },
            modifier = Modifier.statusBarsPadding().navigationBarsPadding().padding(bottom = 80.dp))
    }
    openFolderId?.let { id -> snapshot.folders.firstOrNull { it.id == id }?.let { folder ->
        FolderPopup(folder, catalog.apps, hiddenKeys,
            catalog.profiles.filter { it.quietModeEnabled }.map { it.serialNumber }.toSet(),
            onDismiss = { openFolderId = null }, onLaunch = { openFolderId = null; onLaunch(it) },
            onOpenActions = { openFolderId = null; folderAction = folder.id to it },
            onAddApps = { openFolderId = null; editingFolderId = folder.id },
            onEditFolder = { openFolderId = null; editingFolderId = folder.id },
            anchorBounds = folderBounds[folder.id])
    } }
    editingFolderId?.let { id -> OrganizationFoldersDialog(snapshot, catalog.apps, runtime,
        mostUsedEnabled, initialFolderId = id, onDismiss = { editingFolderId = null }) }
    organizeApp?.let { app -> AppOrganizationPicker(app, snapshot, mostUsedEnabled,
        categoryMembership = { id, key -> snapshot.categories.firstOrNull { it.id == id }?.let { category ->
            catalog.apps.firstOrNull { it.key == key }?.let { CategoryClassifier.membership(snapshot, category, it) }
        } ?: false }, onUpdate = runtime::update, onDismiss = { organizeApp = null }) }
    folderAction?.let { (folderId, app) -> AppActionsSheet(app, rememberAppIcon(app), app.key in pinnedAppKeys,
        onDismiss = { folderAction = null }, onPinApp = onPinApp, onAppInfo = onAppInfo,
        onUninstallApp = onUninstallApp, onRenameApp = onRenameApp, onHideApp = onHideApp,
        onOrganizeApp = { organizeApp = it }, onRemoveFromFolder = {
            snapshot.folders.firstOrNull { it.id == folderId }?.let { folder -> scope.launch {
                runtime.update(OrganizationBatch(listOf(OrganizationEdit.UpdateFolder(
                    folder.copy(members = folder.members.filterNot { key -> key == app.key }))), snapshot.revision))
                    .onFailure { error = it.message ?: "Could not remove app from folder" }
            } }
        }) }
    if (setupSorter) OrganizationSorterSetup(snapshot, runtime,
        onStart = { left, right -> setupSorter = false; onStartSorter(left, right) },
        onDismiss = { setupSorter = false })
    categoryMembers?.let { id -> CategoryMembersDialog(id, snapshot, catalog.apps, runtime::update,
        onDismiss = { categoryMembers = null }) }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("Close") } }) }
}
