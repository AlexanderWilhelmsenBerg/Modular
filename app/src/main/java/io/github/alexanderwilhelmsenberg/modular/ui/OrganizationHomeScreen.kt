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
    widgetContent: @Composable (WorkspaceWidget) -> Unit = {},
    canFitHalfWidget: (WorkspaceWidget, Int) -> Boolean = { _, _ -> true },
    onWidgetSurfaceVisible: (Boolean) -> Unit = {},
    onAddWidget: (String) -> Unit = {},
    onEditWorkspace: (String) -> Unit = {},
    listeningApps: List<LaunchableApp> = emptyList(),
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
    var savedAppsOpen by rememberSaveable { mutableStateOf(false) }
    val vertical = remember { WorkspaceVerticalState(savedAppsOpen) }
    var observedWorkspaceResetToken by rememberSaveable { mutableIntStateOf(homeResetToken) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var pointerActive by remember { mutableStateOf(false) }
    var routeDragToHome by remember { mutableStateOf(false) }
    var routeDragToApps by remember { mutableStateOf(false) }
    var transitionJob by remember { mutableStateOf<Job?>(null) }
    var railJump by remember { mutableStateOf<String?>(null) }
    var railJumpSerial by remember { mutableIntStateOf(0) }
    var starJumpSerial by remember { mutableIntStateOf(0) }
    var requestedPage by remember { mutableStateOf<HomePageId?>(null) }
    var pageRequestSerial by remember { mutableIntStateOf(0) }
    var previousWorkspaceQuery by remember { mutableStateOf(searchQuery) }
    var searchOriginOpen by rememberSaveable { mutableStateOf(false) }
    var activeWorkspaceList by remember { mutableStateOf<LazyListState?>(null) }
    val homeWorkspaceList = rememberLazyListState()
    var retainedListeningApps by remember { mutableStateOf(listeningApps) }
    val workspacePage = pageName == "home" || pageName.startsWith("widget:")
    val widgetSurfaceVisible = workspaceMode && workspacePage && vertical.progress < 1f
    val currentWidgetVisibilityCallback by rememberUpdatedState(onWidgetSurfaceVisible)
    SideEffect { onWidgetSurfaceVisible(widgetSurfaceVisible) }
    DisposableEffect(Unit) {
        onDispose { currentWidgetVisibilityCallback(false) }
    }
    val workspaceGesturesEnabled = workspaceMode && workspacePage && searchQuery.isBlank() &&
        sorterController == null && !editingSearch
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
                settle(true, immediate = true)
            } else if (previousWorkspaceQuery.isNotBlank() && searchQuery.isBlank()) {
                settle(searchOriginOpen, immediate = true)
            }
        }
        previousWorkspaceQuery = searchQuery
    }
    val connection = remember(vertical, workspaceMode, workspacePage, workspaceGesturesEnabled, viewportHeight, pointerActive, activeWorkspaceList) {
        object : NestedScrollConnection {
            fun consumeTransition(deltaY: Float): Float {
                val before = vertical.progress
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
                    val appsScroll = listState.dispatchRawDelta(-remaining)
                    return translated - appsScroll
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
                    val appsTaken = -listState.dispatchRawDelta(-available.y)
                    val remaining = available.y - appsTaken
                    if (remaining > 0f) {
                        routeDragToApps = false
                        return Offset(0f, appsTaken + consumeTransition(remaining))
                    }
                    return Offset(0f, appsTaken)
                }
                if (vertical.progress <= 0f || vertical.progress >= 1f) return Offset.Zero
                return Offset(0f, consumeTransition(available.y))
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || !workspaceGesturesEnabled || !pointerActive) return Offset.Zero
                if ((vertical.progress == 0f && available.y >= 0f) ||
                    (vertical.progress == 1f && available.y <= 0f)) return Offset.Zero
                if (vertical.progress == 0f && available.y < 0f) {
                    railJump = null
                    railJumpSerial += 1
                    listState.requestScrollToItem(0)
                }
                if (vertical.progress == 1f && available.y > 0f) activeWorkspaceList?.let { state ->
                    state.requestScrollToItem((state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                }
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
    var pagerScrolling by remember { mutableStateOf(false) }
    val pageScrolling = pagerScrolling || scrollingPages.values.any { it }
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
            railJump = null
            searchOriginOpen = false
            requestedPage = HomePageId.Home
            pageRequestSerial += 1
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
        pageName.startsWith("category:") -> HomePageId.Category(pageName.removePrefix("category:"))
        else -> HomePageId.Category(pageName)
    }
    Box(Modifier.fillMaxSize()
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
        LauncherPages(snapshot = if (sorter != null) snapshot.copy(categoriesEnabled = false) else snapshot,
            gesturesEnabled = !editingSearch && searchQuery.isBlank() && sorter == null &&
                openFolderId == null && organizeApp == null && folderAction == null &&
                categoryMembers == null && editingFolderId == null && !setupSorter && vertical.progress == 0f,
            homeResetToken = homeResetToken, initialPageId = initialPage,
            requestedPage = requestedPage, pageRequestSerial = pageRequestSerial,
            modifier = Modifier.offset { IntOffset(0, if (workspaceMode && workspacePage) (-vertical.progress * viewportHeight).roundToInt() else 0) },
            onScrollingChange = { pagerScrolling = it },
            onPageChanged = { pageName = when (it) {
                HomePageId.Home -> "home"
                is HomePageId.Category -> "category:${it.id}"
                is HomePageId.Widget -> "widget:${it.id}"
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
                if (workspaceMode && (page == HomePageId.Home || page is HomePageId.Widget) && sorter == null) {
                    val pageKey = if (page == HomePageId.Home) "home" else "widget:${(page as HomePageId.Widget).id}"
                    if (pageName == pageKey) SideEffect { activeWorkspaceList = pageList }
                    WorkspaceHome(
                        pageId = pageKey,
                        pageTitle = widgetPage?.name,
                        preferences = snapshot.workspacePreferences,
                        widgets = snapshot.widgets,
                        canFitHalfWidget = canFitHalfWidget,
                        widgetContent = { widget ->
                            var slotHeightDp by rememberSaveable(widget.appWidgetId, widget.heightDp, widget.paddingDp) {
                                mutableIntStateOf(widget.heightDp + widget.paddingDp * 2)
                            }
                            if (widgetSurfaceVisible && pageName == pageKey) {
                                Box(Modifier.fillMaxWidth().onSizeChanged { size ->
                                    if (size.height > 0) slotHeightDp = with(density) { size.height.toDp().value.roundToInt() }
                                }) { widgetContent(widget) }
                            } else {
                                Spacer(Modifier.fillMaxWidth().height(slotHeightDp.dp))
                            }
                        },
                        featuredApps = if (page == HomePageId.Home) featured else null,
                        featuredFolders = if (page == HomePageId.Home && mostUsedEnabled)
                            projection.mostUsedFolders.map { it.definition } else emptyList(),
                        featuredMessage = featuredMessage,
                        featuredNeedsPermission = featuredNeedsPermission,
                        onOpenFeaturedSettings = onOpenFeaturedSettings,
                        onOpenFeaturedManagement = onOpenFeaturedManagement,
                        pinOrder = projection.visiblePinRefs,
                        listeningApps = if (page == HomePageId.Home) retainedListeningApps else emptyList(),
                        pinnedKeys = pinnedAppKeys,
                        listState = pageList,
                        wallpaperMode = wallpaperMode,
                        showIcons = showAppIcons,
                        onLaunch = onLaunch,
                        onPinApp = onPinApp, onAppInfo = onAppInfo, onUninstallApp = onUninstallApp,
                        onRenameApp = onRenameApp, onHideApp = onHideApp,
                        onOrganizeApp = { organizeApp = it },
                        onOpenFolder = { openFolderId = it.id }, onEditFolder = { editingFolderId = it.id },
                        onFolderPress = { folderPressed = it },
                        onFolderBounds = { id, bounds -> folderBounds[id] = bounds },
                        onAddWidget = { onAddWidget(pageKey) },
                        onEditWorkspace = { onEditWorkspace(pageKey) },
                        starJumpSerial = starJumpSerial,
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
                    featuredApps = if (workspaceMode) null else featured,
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
                    featuredFolders = if (!workspaceMode && category == null && sorter == null && mostUsedEnabled)
                        projection.mostUsedFolders.map { it.definition } else emptyList(),
                    featuredPinOrder = if (!workspaceMode && mostUsedEnabled) projection.visiblePinRefs else emptyList(),
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
        if (workspaceMode && workspacePage && sorter == null) {
            Box(Modifier.fillMaxSize()
                .offset { IntOffset(0, ((1f - vertical.progress) * viewportHeight).roundToInt()) }
                .testTag("workspace-all-apps")) {
                HomeScreen(
                    catalog = catalog.copy(apps = projection.homeApps),
                    searchApps = projection.globalSearchApps,
                    orderedApps = orderedApps?.filter { it.key in projection.homeKeys },
                    listState = listState,
                    onLaunch = onLaunch, onRefresh = onRefresh, onMakeDefault = onMakeDefault,
                    onOpenSettings = onOpenSettings, showMakeDefault = showMakeDefault,
                    searchQuery = searchQuery, onSearchQueryChange = onSearchQueryChange,
                    showAppIcons = showAppIcons, wallpaperMode = wallpaperMode,
                    wallpaperDimmingPercent = wallpaperDimmingPercent,
                    featuredApps = null, pinnedAppKeys = pinnedAppKeys,
                    onPinApp = onPinApp, onAppInfo = onAppInfo, onUninstallApp = onUninstallApp,
                    onRenameApp = onRenameApp, onHideApp = onHideApp,
                    onOrganizeApp = { organizeApp = it },
                    alphabeticalSections = true,
                    homeResetToken = homeResetToken,
                    folders = projection.appListFolders.map { it.definition },
                    onOpenFolder = { openFolderId = it.id }, onEditFolder = { editingFolderId = it.id },
                    onFolderPress = { folderPressed = it },
                    onFolderBounds = { id, bounds -> folderBounds[id] = bounds },
                    showChrome = false, showAlphabetRail = false, enableReachability = false,
                    externalJump = railJump, externalJumpSerial = railJumpSerial,
                    drawWallpaperScrim = false,
                )
            }
            val locale = LocalConfiguration.current.locales[0]
            val sections = remember(projection.homeApps, locale) {
                HomeSectionBuilder(locale).sections(homeOrderedApps(projection.homeApps, orderedApps?.filter { it.key in projection.homeKeys }, locale))
            }
            val hasFeatured = featuredApps != null || (mostUsedEnabled && projection.mostUsedFolders.isNotEmpty())
            val alphabet = remember(sections, projection.appListFolders, hasFeatured) {
                (if (hasFeatured) listOf("★") else emptyList()) + sections.map { it.label } +
                    (if (projection.appListFolders.isNotEmpty()) listOf("◇") else emptyList())
            }
            if (searchQuery.isBlank() && !editingSearch && alphabet.isNotEmpty()) AlphabetRail(
                alphabet = alphabet,
                populated = alphabet.toSet(),
                onSelect = { label ->
                    if (label == "★") {
                        requestedPage = HomePageId.Home
                        pageRequestSerial += 1
                        starJumpSerial += 1
                        settle(false)
                    }
                    else {
                        railJump = if (label == "◇") "Folders" else label
                        railJumpSerial += 1
                        settle(true)
                    }
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
                    modifier = Modifier.width(72.dp).testTag(if (vertical.progress < 1f) "workspace-apps-action" else "workspace-home-action"),
                ) { Text(if (vertical.progress < 1f) "Apps" else "Home") }
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
