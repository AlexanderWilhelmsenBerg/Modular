package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.apps.*
import io.github.alexanderwilhelmsenberg.modular.appearance.WallpaperHomeScrim
import io.github.alexanderwilhelmsenberg.modular.organization.*
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import io.github.alexanderwilhelmsenberg.modular.widgets.WidgetLayoutLimits
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    workspaceMode: Boolean = false,
    widgetContent: @Composable (WorkspaceWidget, () -> Unit) -> Unit = { _, _ -> },
    canFitHalfWidget: (WorkspaceWidget, Int) -> Boolean = { _, _ -> true },
    widgetLayoutLimits: (WorkspaceWidget) -> WidgetLayoutLimits? = { null },
    onSaveWidgetGrid: suspend (Int, Int, Int, Int, Int) -> Result<Unit> = { _, _, _, _, _ -> Result.success(Unit) },
    onRemoveWidget: suspend (Int) -> Result<Unit> = {
        Result.failure(IllegalStateException("Widget removal is unavailable"))
    },
    onWidgetSurfaceVisible: (Boolean) -> Unit = {},
    onAddWidget: (String) -> Unit = {},
    onEditWorkspace: (String) -> Unit = {},
    onOpenWorkspaceAdvanced: (String) -> Unit = onEditWorkspace,
    listeningApps: List<LaunchableApp> = emptyList(),
    modulePageContent: @Composable (String, Boolean, (Boolean) -> Unit, () -> Unit) -> Unit = { _, _, _, _ -> },
    onActiveModuleChanged: (String?) -> Unit = {},
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
    var editingWidgetGrid by remember { mutableStateOf(false) }
    var setupSorter by remember { mutableStateOf(false) }
    var categoryMembers by remember { mutableStateOf<String?>(null) }
    var pageName by rememberSaveable { mutableStateOf("home") }
    LaunchedEffect(pageName, workspaceMode) {
        onActiveModuleChanged(pageName.removePrefix("module:")
            .takeIf { workspaceMode && pageName.startsWith("module:") })
    }
    DisposableEffect(Unit) { onDispose { onActiveModuleChanged(null) } }
    var drawerPageName by rememberSaveable { mutableStateOf("home") }
    var savedAppsOpen by rememberSaveable { mutableStateOf(false) }
    val vertical = remember { WorkspaceVerticalState(savedAppsOpen) }
    var observedWorkspaceResetToken by rememberSaveable { mutableIntStateOf(homeResetToken) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var pointerActive by remember { mutableStateOf(false) }
    var routeDragToHome by remember { mutableStateOf(false) }
    var routeDragToApps by remember { mutableStateOf(false) }
    var transitionJob by remember { mutableStateOf<Job?>(null) }
    var railJump by remember { mutableStateOf<String?>(null) }
    var railJumpPage by remember { mutableStateOf<HomePageId>(HomePageId.Home) }
    var railJumpSerial by remember { mutableIntStateOf(0) }
    var requestedPage by remember { mutableStateOf<HomePageId?>(null) }
    var pageRequestSerial by remember { mutableIntStateOf(0) }
    var requestedDrawerPage by remember { mutableStateOf<HomePageId?>(null) }
    var drawerRequestSerial by remember { mutableIntStateOf(0) }
    var previousWorkspaceQuery by remember { mutableStateOf(searchQuery) }
    var searchOriginOpen by rememberSaveable { mutableStateOf(false) }
    var searchOriginDrawerPage by rememberSaveable { mutableStateOf("home") }
    var activeWorkspaceList by remember { mutableStateOf<LazyListState?>(null) }
    var activeDrawerList by remember { mutableStateOf<LazyListState?>(null) }
    var moduleInteractionActive by remember { mutableStateOf(false) }
    val homeWorkspaceList = rememberLazyListState()
    var retainedListeningApps by remember { mutableStateOf(listeningApps) }
    val workspacePage = pageName == "home" || pageName.startsWith("widget:") || pageName.startsWith("module:")
    val widgetSurfaceVisible = workspaceMode && workspacePage && vertical.progress < 1f
    val currentWidgetVisibilityCallback by rememberUpdatedState(onWidgetSurfaceVisible)
    SideEffect { onWidgetSurfaceVisible(widgetSurfaceVisible) }
    DisposableEffect(Unit) {
        onDispose { currentWidgetVisibilityCallback(false) }
    }
    val workspaceGesturesEnabled = workspaceMode && workspacePage && searchQuery.isBlank() &&
        sorterController == null && !editingSearch && !editingWidgetGrid && !moduleInteractionActive
    fun settle(open: Boolean, immediate: Boolean = false) {
        transitionJob?.cancel()
        savedAppsOpen = open
        transitionJob = scope.launch {
            if (immediate) vertical.snap(open)
            else animate(vertical.progress, if (open) 1f else 0f, animationSpec = tween(260)) { value, _ ->
                vertical.updateProgress(value)
            }
        }
    }
    LaunchedEffect(searchQuery, workspaceMode) {
        if (workspaceMode) {
            if (previousWorkspaceQuery.isBlank() && searchQuery.isNotBlank()) {
                searchOriginOpen = vertical.progress >= 0.5f
                searchOriginDrawerPage = drawerPageName
                requestedDrawerPage = HomePageId.Home
                drawerRequestSerial += 1
                settle(true, immediate = true)
            } else if (previousWorkspaceQuery.isNotBlank() && searchQuery.isBlank()) {
                requestedDrawerPage = if (searchOriginDrawerPage.startsWith("category:"))
                    HomePageId.Category(searchOriginDrawerPage.removePrefix("category:")) else HomePageId.Home
                drawerRequestSerial += 1
                settle(searchOriginOpen, immediate = true)
            }
        }
        previousWorkspaceQuery = searchQuery
    }
    val connection = remember(vertical, workspaceMode, workspacePage, workspaceGesturesEnabled,
        viewportHeight, pointerActive, activeWorkspaceList, activeDrawerList) {
        object : NestedScrollConnection {
            fun consumeTransition(deltaY: Float): Float {
                val before = vertical.progress
                if (before == 0f && deltaY < 0f) {
                    // A new Home swipe opens the central drawer at its first section,
                    // regardless of the previous category, letter jump, or scroll.
                    requestedDrawerPage = HomePageId.Home
                    drawerRequestSerial += 1
                    railJumpPage = HomePageId.Home
                    railJump = null
                    railJumpSerial += 1
                    listState.requestScrollToItem(0)
                    activeDrawerList = listState
                }
                val translated = vertical.drag(deltaY, viewportHeight.toFloat())
                if (before > 0f && vertical.progress == 0f && deltaY > 0f) routeDragToHome = true
                if (before < 1f && vertical.progress == 1f && deltaY < 0f) routeDragToApps = true
                // The original child keeps this pointer stream. Forward movement
                // beyond the endpoint to the surface that just entered view.
                val remaining = deltaY - translated
                if (deltaY > 0f && translated > 0f && vertical.progress == 0f && remaining > 0f) {
                    val homeScroll = activeWorkspaceList?.dispatchRawDelta(-remaining) ?: 0f
                    return translated - homeScroll
                }
                if (deltaY < 0f && translated < 0f && vertical.progress == 1f && remaining < 0f) {
                    // Finish the opening gesture at the top, without carrying
                    // its remaining travel into Most Used. A fresh swipe scrolls.
                    return deltaY
                }
                return translated
            }
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || !workspaceGesturesEnabled || !pointerActive) return Offset.Zero
                if (routeDragToHome && vertical.progress == 0f) {
                    val homeTaken = -(activeWorkspaceList?.dispatchRawDelta(-available.y) ?: 0f)
                    val remaining = available.y - homeTaken
                    if (remaining < 0f) {
                        routeDragToHome = false
                        return Offset(0f, homeTaken + consumeTransition(remaining))
                    }
                    return Offset(0f, homeTaken)
                }
                if (routeDragToApps && vertical.progress == 1f) {
                    if (available.y > 0f) {
                        routeDragToApps = false
                        return Offset(0f, consumeTransition(available.y))
                    }
                    return Offset(0f, available.y)
                }
                if (vertical.progress <= 0f || vertical.progress >= 1f) return Offset.Zero
                return Offset(0f, consumeTransition(available.y))
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || !workspaceGesturesEnabled || !pointerActive) return Offset.Zero
                if ((vertical.progress == 0f && available.y >= 0f) ||
                    (vertical.progress == 1f && available.y <= 0f)) return Offset.Zero
                return Offset(0f, consumeTransition(available.y))
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (vertical.progress > 0f && vertical.progress < 1f) {
                    settle(vertical.releaseTarget(available.y))
                    return available
                }
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    val scrollingPages = remember { mutableStateMapOf<HomePageId, Boolean>() }
    val scrollingDrawerPages = remember { mutableStateMapOf<HomePageId, Boolean>() }
    var pagerScrolling by remember { mutableStateOf(false) }
    var drawerPagerScrolling by remember { mutableStateOf(false) }
    val pageScrolling = pagerScrolling || drawerPagerScrolling || scrollingPages.values.any { it } ||
        scrollingDrawerPages.values.any { it }
    val folderBounds = remember { mutableStateMapOf<String, androidx.compose.ui.unit.IntRect>() }
    LaunchedEffect(statuses, folderPressed, openFolderId, pageScrolling) {
        if (!folderPressed && openFolderId == null && !pageScrolling)
            retainedVisibility = statuses.mapValues { it.value.eligible }
    }
    LaunchedEffect(listeningApps, pointerActive, pageScrolling, folderPressed, openFolderId) {
        if (!pointerActive && !pageScrolling && !folderPressed && openFolderId == null)
            retainedListeningApps = listeningApps
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
        if (workspaceMode && homeResetToken != observedWorkspaceResetToken) {
            editingWidgetGrid = false
            railJump = null
            railJumpPage = HomePageId.Home
            searchOriginOpen = false
            requestedPage = HomePageId.Home
            pageRequestSerial += 1
            requestedDrawerPage = HomePageId.Home
            drawerRequestSerial += 1
            homeWorkspaceList.requestScrollToItem(0)
            listState.requestScrollToItem(0)
            settle(false, immediate = true)
        }
        observedWorkspaceResetToken = homeResetToken
    }
    BackHandler(enabled = searchQuery.isNotBlank() || sorterController != null || (workspaceMode && vertical.progress > 0f)) {
        when {
            searchQuery.isNotBlank() -> onSearchQueryChange("")
            sorterController != null -> onExitSorter()
            else -> settle(false)
        }
    }
    val initialPage = when {
        pageName == "home" -> HomePageId.Home
        pageName.startsWith("widget:") -> HomePageId.Widget(pageName.removePrefix("widget:"))
        pageName.startsWith("module:") -> HomePageId.Module(pageName.removePrefix("module:"))
        pageName.startsWith("category:") -> HomePageId.Category(pageName.removePrefix("category:"))
        else -> HomePageId.Category(pageName)
    }
    Box(Modifier.fillMaxSize().clipToBounds()
        .background(if (wallpaperMode) Color.Transparent else MaterialTheme.colorScheme.background)
        .onSizeChanged { viewportHeight = it.height }
        .then(if (workspaceMode) Modifier.nestedScroll(connection).pointerInput(workspaceGesturesEnabled) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                transitionJob?.cancel()
                pointerActive = true
                routeDragToHome = false
                routeDragToApps = false
                try {
                    var pressed: Boolean
                    do {
                        pressed = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                    } while (pressed)
                } finally {
                    pointerActive = false
                    routeDragToHome = false
                    routeDragToApps = false
                    when {
                        vertical.progress == 0f -> savedAppsOpen = false
                        vertical.progress == 1f -> savedAppsOpen = true
                        else -> settle(vertical.releaseTarget())
                    }
                }
            }
        } else Modifier)) {
        if (workspaceMode && wallpaperMode) WallpaperHomeScrim(wallpaperDimmingPercent, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().clipToBounds()) {
        LauncherPages(snapshot = if (sorter != null) snapshot.copy(categoriesEnabled = false) else snapshot,
            pageSequence = if (workspaceMode) snapshot.workspaceSurfacePages() else
                (if (sorter != null) snapshot.copy(categoriesEnabled = false) else snapshot).visiblePageOrder(),
            gesturesEnabled = !editingSearch && searchQuery.isBlank() && sorter == null &&
                openFolderId == null && organizeApp == null && folderAction == null &&
                categoryMembers == null && editingFolderId == null && !setupSorter && !editingWidgetGrid &&
                !moduleInteractionActive && vertical.progress == 0f,
            homeResetToken = homeResetToken, initialPageId = initialPage,
            requestedPage = requestedPage, pageRequestSerial = pageRequestSerial,
            modifier = Modifier.offset { IntOffset(0, if (workspaceMode && workspacePage) (-vertical.progress * viewportHeight).roundToInt() else 0) },
            onScrollingChange = { pagerScrolling = it },
            onPageChanged = {
                if (it !is HomePageId.Module) moduleInteractionActive = false
                pageName = when (it) {
                HomePageId.Home -> "home"
                is HomePageId.Category -> "category:${it.id}"
                is HomePageId.Widget -> "widget:${it.id}"
                is HomePageId.Module -> "module:${it.component}"
            } },
            renderPage = { page ->
                val category = (page as? HomePageId.Category)?.let { id -> snapshot.categories.firstOrNull { it.id == id.id } }
                val widgetPage = (page as? HomePageId.Widget)?.let { id -> snapshot.workspacePages.firstOrNull { it.id == id.id } }
                val apps = when {
                    sorter != null -> sorter.rows.map { it.app.copy(enabled = it.available && it.app.enabled) }
                    category != null -> projection.categoryPages[category.id].orEmpty()
                    else -> projection.homeApps
                }
                val pageList = when {
                    page == HomePageId.Home && !workspaceMode -> listState
                    page == HomePageId.Home -> homeWorkspaceList
                    else -> rememberLazyListState()
                }
                LaunchedEffect(page, pageList.isScrollInProgress) {
                    scrollingPages[page] = pageList.isScrollInProgress
                }
                DisposableEffect(page) { onDispose { scrollingPages.remove(page) } }
                val featured = if (category == null && sorter == null) featuredApps?.let(projection::filterDirectStarred) else null
                if (workspaceMode && page is HomePageId.Module && sorter == null) {
                    if (pageName == "module:${page.component}") SideEffect { activeWorkspaceList = null }
                    Box(Modifier.fillMaxSize().padding(bottom = 72.dp, end = 28.dp)) {
                        modulePageContent(page.component, pageName == "module:${page.component}",
                            { moduleInteractionActive = it },
                            { requestedPage = HomePageId.Home; pageRequestSerial += 1 })
                    }
                } else if (workspaceMode && (page == HomePageId.Home || page is HomePageId.Widget) && sorter == null) {
                    val pageKey = if (page == HomePageId.Home) "home" else "widget:${(page as HomePageId.Widget).id}"
                    if (pageName == pageKey) SideEffect { activeWorkspaceList = pageList }
                    WorkspaceHome(
                        pageId = pageKey,
                        pageTitle = widgetPage?.name,
                        preferences = snapshot.workspacePreferences,
                        widgets = snapshot.widgets,
                        canFitHalfWidget = canFitHalfWidget,
                        widgetContent = { widget, onLongPress ->
                            var slotHeightDp by rememberSaveable(widget.appWidgetId, widget.heightDp, widget.paddingDp) {
                                mutableIntStateOf(widget.heightDp + widget.paddingDp * 2)
                            }
                            if (widgetSurfaceVisible && pageName == pageKey) {
                                Box(Modifier.fillMaxWidth().onSizeChanged { size ->
                                    if (size.height > 0) slotHeightDp = with(density) { size.height.toDp().value.roundToInt() }
                                }) { widgetContent(widget, onLongPress) }
                            } else {
                                Spacer(Modifier.fillMaxWidth().height(slotHeightDp.dp))
                            }
                        },
                        listeningApps = if (page == HomePageId.Home) retainedListeningApps else emptyList(),
                        pinnedKeys = pinnedAppKeys,
                        listState = pageList,
                        wallpaperMode = wallpaperMode,
                        showIcons = showAppIcons,
                        onLaunch = onLaunch,
                        onPinApp = onPinApp, onAppInfo = onAppInfo, onUninstallApp = onUninstallApp,
                        onRenameApp = onRenameApp, onHideApp = onHideApp,
                        onOrganizeApp = { organizeApp = it },
                        onAddWidget = { onAddWidget(pageKey) },
                        onEditWorkspace = { onEditWorkspace(pageKey) },
                        onOpenWorkspaceAdvanced = { onOpenWorkspaceAdvanced(pageKey) },
                        widgetLayoutLimits = widgetLayoutLimits,
                        onSaveWidgetGrid = onSaveWidgetGrid,
                        onRemoveWidget = onRemoveWidget,
                        onEditingChange = { editingWidgetGrid = it },
                        pageActive = pageName == pageKey,
                        clockActive = pageName == pageKey && vertical.progress < 1f,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else HomeScreen(
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
                    featuredFolders = if (category == null && sorter == null)
                        projection.mostUsedFolders.map { it.definition } else emptyList(),
                    featuredPinOrder = if (category == null && sorter == null)
                        drawerPinOrder(projection.visiblePinRefs, mostUsedEnabled) else emptyList(),
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
                    drawWallpaperScrim = !workspaceMode,
                )
            })
        }
        if (workspaceMode && workspacePage && sorter == null) {
            Box(Modifier.fillMaxSize().clipToBounds()
                .offset { IntOffset(0, ((1f - vertical.progress) * viewportHeight).roundToInt()) }
                .testTag("workspace-all-apps")) {
                LauncherPages(
                    snapshot = snapshot,
                    pageSequence = snapshot.drawerSurfacePages(),
                    testTag = "drawer-pages",
                    handleBack = false,
                    gesturesEnabled = vertical.progress == 1f && searchQuery.isBlank() &&
                        !editingSearch && !editingWidgetGrid && openFolderId == null &&
                        organizeApp == null && folderAction == null && categoryMembers == null &&
                        editingFolderId == null && !setupSorter,
                    homeResetToken = homeResetToken,
                    initialPageId = if (drawerPageName.startsWith("category:"))
                        HomePageId.Category(drawerPageName.removePrefix("category:")) else HomePageId.Home,
                    requestedPage = requestedDrawerPage,
                    pageRequestSerial = drawerRequestSerial,
                    onScrollingChange = { drawerPagerScrolling = it },
                    onPageChanged = { drawerPageName = when (it) {
                        HomePageId.Home -> "home"
                        is HomePageId.Category -> "category:${it.id}"
                        is HomePageId.Widget -> "home"
                        is HomePageId.Module -> "home"
                    } },
                    renderPage = { page ->
                        val category = (page as? HomePageId.Category)?.let { id ->
                            snapshot.categories.firstOrNull { it.id == id.id }
                        }
                        val pageKey = if (category == null) "home" else "category:${category.id}"
                        val pageList = if (category == null) listState else rememberLazyListState()
                        if (drawerPageName == pageKey) SideEffect { activeDrawerList = pageList }
                        LaunchedEffect(page, pageList.isScrollInProgress) {
                            scrollingDrawerPages[page] = pageList.isScrollInProgress
                        }
                        DisposableEffect(page) { onDispose { scrollingDrawerPages.remove(page) } }
                        HomeScreen(
                            catalog = catalog.copy(apps = if (category == null) projection.homeApps
                                else projection.categoryPages[category.id].orEmpty()),
                            searchApps = projection.globalSearchApps,
                            orderedApps = if (category == null)
                                orderedApps?.filter { it.key in projection.homeKeys } else null,
                            pageTitle = category?.name,
                            listState = pageList,
                            onLaunch = onLaunch, onRefresh = onRefresh, onMakeDefault = onMakeDefault,
                            onOpenSettings = onOpenSettings,
                            showMakeDefault = showMakeDefault && category == null,
                            searchQuery = searchQuery, onSearchQueryChange = onSearchQueryChange,
                            showAppIcons = showAppIcons, wallpaperMode = wallpaperMode,
                            wallpaperDimmingPercent = wallpaperDimmingPercent,
                            featuredApps = if (category == null)
                                featuredApps?.let(projection::filterDirectStarred) else null,
                            pinnedAppKeys = pinnedAppKeys, featuredMessage = featuredMessage,
                            featuredNeedsPermission = featuredNeedsPermission,
                            onOpenFeaturedSettings = onOpenFeaturedSettings,
                            onOpenFeaturedManagement = onOpenFeaturedManagement,
                            onPinApp = onPinApp, onAppInfo = onAppInfo, onUninstallApp = onUninstallApp,
                            onRenameApp = onRenameApp, onHideApp = onHideApp,
                            onOrganizeApp = { organizeApp = it },
                            alphabeticalSections = true,
                            homeResetToken = homeResetToken,
                            folders = if (category == null) projection.appListFolders.map { it.definition }
                                else emptyList(),
                            featuredFolders = if (category == null) projection.mostUsedFolders.map { it.definition }
                                else emptyList(),
                            featuredPinOrder = if (category == null)
                                drawerPinOrder(projection.visiblePinRefs, mostUsedEnabled) else emptyList(),
                            onOpenFolder = { openFolderId = it.id },
                            onEditFolder = { editingFolderId = it.id },
                            onFolderPress = { folderPressed = it },
                            onFolderBounds = { id, bounds -> folderBounds[id] = bounds },
                            onAddCategoryApps = if (category != null) ({ categoryMembers = category.id }) else null,
                            onStartCategorySorter = if (category != null) ({ setupSorter = true }) else null,
                            showChrome = false, showAlphabetRail = false, enableReachability = false,
                            externalJump = if (railJumpPage == page) railJump else null,
                            externalJumpSerial = if (railJumpPage == page) railJumpSerial else 0,
                            drawWallpaperScrim = false,
                        )
                    },
                )
            }
            val locale = LocalConfiguration.current.locales[0]
            val categoryRailId = drawerPageName.removePrefix("category:")
                .takeIf { drawerPageName.startsWith("category:") && vertical.progress == 1f }
            val categoryRailApps = categoryRailId?.let { projection.categoryPages[it].orEmpty() }
            val railApps = categoryRailApps ?: projection.homeApps
            val sections = remember(railApps, orderedApps, categoryRailId, locale) {
                val order = if (categoryRailId == null)
                    orderedApps?.filter { it.key in projection.homeKeys } else null
                HomeSectionBuilder(locale).sections(homeOrderedApps(railApps, order, locale))
            }
            val hasFeatured = featuredApps != null
            val hasFolders = projection.mostUsedFolders.isNotEmpty() || projection.appListFolders.isNotEmpty()
            val alphabet = remember(sections, hasFolders, hasFeatured, categoryRailId) {
                if (categoryRailId != null) sections.map { it.label } else
                    (if (hasFeatured) listOf("★") else emptyList()) +
                        (if (hasFolders) listOf("◇") else emptyList()) + sections.map { it.label }
            }
            if (searchQuery.isBlank() && !editingSearch && !editingWidgetGrid &&
                !drawerPagerScrolling && alphabet.isNotEmpty()) AlphabetRail(
                alphabet = alphabet,
                populated = alphabet.toSet(),
                onSelect = { label ->
                    val target = categoryRailId?.let { HomePageId.Category(it) } ?: HomePageId.Home
                    railJumpPage = target
                    railJump = if (label == "◇") "Folders" else label
                    railJumpSerial += 1
                    if (target == HomePageId.Home) {
                        requestedDrawerPage = HomePageId.Home
                        drawerRequestSerial += 1
                    }
                    settle(true)
                },
                wallpaperMode = wallpaperMode,
                modifier = Modifier.align(AbsoluteAlignment.BottomRight).navigationBarsPadding().padding(bottom = 72.dp),
            )
            val focusRequester = remember { FocusRequester() }
            val focusManager = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            BackHandler(enabled = editingSearch) { keyboard?.hide(); focusManager.clearFocus() }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding()
                .background(if (wallpaperMode) Color.Black.copy(alpha = 0.65f) else MaterialTheme.colorScheme.background),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { settle(vertical.progress < 1f) },
                    enabled = !editingWidgetGrid,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.width(72.dp).testTag(if (vertical.progress < 1f) "workspace-apps-action" else "workspace-home-action"),
                ) { Text(if (vertical.progress < 1f) "Apps" else "Home", maxLines = 1) }
                Box(Modifier.weight(1f)) {
                    HomeBottomControls(
                        query = searchQuery,
                        onQueryChange = onSearchQueryChange,
                        focusRequester = focusRequester,
                        onEditingChange = { editingSearch = it },
                        onSearchAction = { keyboard?.hide(); focusManager.clearFocus() },
                        onOpenSettings = onOpenSettings,
                        wallpaperMode = wallpaperMode,
                    )
                }
            }
        }
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
