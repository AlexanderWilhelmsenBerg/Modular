package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.apps.rememberAppIcon
import io.github.alexanderwilhelmsenberg.modular.appearance.WallpaperHomeScrim
import io.github.alexanderwilhelmsenberg.modular.organization.FolderDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.HomePinRef

@Composable
fun HomeScreen(
    catalog: AppCatalog,
    onLaunch: (LaunchableApp) -> Unit,
    onRefresh: () -> Unit,
    onMakeDefault: () -> Unit,
    onOpenSettings: () -> Unit,
    showMakeDefault: Boolean = true,
    listState: LazyListState = rememberLazyListState(),
    orderedApps: List<LaunchableApp>? = null,
    searchQuery: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
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
    searchApps: List<LaunchableApp> = catalog.apps,
    pageTitle: String? = null,
    folders: List<FolderDefinition> = emptyList(),
    featuredFolders: List<FolderDefinition> = emptyList(),
    featuredPinOrder: List<HomePinRef> = emptyList(),
    onOpenFolder: (FolderDefinition) -> Unit = {},
    onEditFolder: (FolderDefinition) -> Unit = {},
    onFolderPress: (Boolean) -> Unit = {},
    onFolderBounds: (String, androidx.compose.ui.unit.IntRect) -> Unit = { _, _ -> },
    onOrganizeApp: ((LaunchableApp) -> Unit)? = null,
    onEditingChange: (Boolean) -> Unit = {},
    onAddCategoryApps: (() -> Unit)? = null,
    onStartCategorySorter: (() -> Unit)? = null,
    appRowContent: (@Composable (LaunchableApp, @Composable () -> Unit) -> Unit)? = null,
    listTopInset: Dp = 0.dp,
    iconForApp: @Composable (LaunchableApp) -> ImageBitmap? = { rememberAppIcon(it) },
    showChrome: Boolean = true,
    showAlphabetRail: Boolean = true,
    enableReachability: Boolean = true,
    externalJump: String? = null,
    externalJumpSerial: Int = 0,
    drawWallpaperScrim: Boolean = true,
) {
    val locale = LocalConfiguration.current.locales[0]
    val normalApps = remember(catalog.apps, orderedApps, locale) { homeOrderedApps(catalog.apps, orderedApps, locale) }
    val alphabeticalApps = remember(searchApps, locale) { homeOrderedApps(searchApps, null, locale) }
    var localQuery by rememberSaveable { mutableStateOf("") }
    val query = searchQuery ?: localQuery
    val searching = query.isNotBlank()
    val visibleApps = remember(normalApps, alphabeticalApps, query, locale) {
        if (query.isBlank()) normalApps else filterHomeApps(alphabeticalApps, query, locale)
    }
    val sectionBuilder = remember(locale) { HomeSectionBuilder(locale) }
    val pausedProfiles = remember(catalog.profiles) { catalog.profiles.filter { it.quietModeEnabled }.map { it.serialNumber }.toSet() }
    val hasPausedProfileApps = catalog.apps.any { it.key.profileSerial in pausedProfiles }
    val currentByKey = remember(catalog.apps) { catalog.apps.associateBy { it.key } }
    val safeFeatured = remember(featuredApps, currentByKey) {
        featuredApps?.mapNotNull { currentByKey[it.key] }?.distinctBy { it.key }
    }
    val useSections = alphabeticalSections && !searching
    val sections = remember(visibleApps, sectionBuilder, useSections) {
        if (useSections) sectionBuilder.sections(visibleApps) else emptyList()
    }
    val entries = remember(sections, visibleApps, safeFeatured, featuredMessage, featuredNeedsPermission, searching, useSections, hasPausedProfileApps, catalog.isRefreshing, catalog.lastError, showMakeDefault, folders, featuredFolders, featuredPinOrder, pageTitle) {
        buildList {
            if (!searching && pageTitle != null) add(HomeListEntry.PageTitle(pageTitle))
            if (hasPausedProfileApps) add(HomeListEntry.Notice("paused-profile-notice", "Some apps are unavailable while a profile is paused."))
            if (catalog.lastError != null) add(HomeListEntry.Error)
            if (catalog.isRefreshing && normalApps.isEmpty() && catalog.lastError == null) add(HomeListEntry.Loading)
            else if (!searching && normalApps.isEmpty() && catalog.lastError == null) add(HomeListEntry.Empty)
            else if (visibleApps.isEmpty() && searching && catalog.lastError == null) add(HomeListEntry.NoResults)
            if (!searching) addAll(featuredHomeEntries(safeFeatured, featuredFolders, featuredPinOrder,
                showStatus = !featuredMessage.isNullOrBlank() || featuredNeedsPermission))
            if (useSections) sections.forEach { section ->
                add(HomeListEntry.Header(section.label))
                section.apps.forEach { add(HomeListEntry.App(it)) }
            } else visibleApps.forEach { add(HomeListEntry.App(it)) }
            if (!searching && folders.isNotEmpty()) {
                add(HomeListEntry.Header("Folders"))
                folders.forEach { add(HomeListEntry.Folder(it)) }
            }
            add(HomeListEntry.Actions)
        }
    }
    var anchorableEntries by remember { mutableStateOf(entries) }
    SideEffect { if (!searching) anchorableEntries = entries }
    val headerIndices = remember(entries) { entries.mapIndexedNotNull { i, entry -> (entry as? HomeListEntry.Header)?.let { it.navigationKey to i + 1 } }.toMap() }
    val alphabet = remember(sections, safeFeatured, featuredFolders, folders) {
        (if (safeFeatured != null || featuredFolders.isNotEmpty()) listOf("★") else emptyList()) + sections.map { it.label } +
            (if (folders.isNotEmpty()) listOf("◇") else emptyList())
    }
    val reachability = rememberSaveable(saver = Saver(save = { it.offsetPx }, restore = { HomeReachabilityState(it) })) { HomeReachabilityState() }
    val density = LocalDensity.current
    var viewportHeight by remember { mutableIntStateOf(0) }
    var navigationSpacePx by rememberSaveable { mutableIntStateOf(0) }
    var jumpHeaderLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var jump by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var jumpSerial by remember { mutableIntStateOf(0) }
    LaunchedEffect(externalJump, externalJumpSerial) {
        if (externalJump != null) {
            jumpSerial += 1
            jump = externalJump to jumpSerial
        } else if (externalJumpSerial > 0) {
            jumpHeaderLabel = null
            navigationSpacePx = 0
        }
    }
    var savedAnchorIndex by rememberSaveable { mutableIntStateOf(-1) }
    var savedAnchorOffset by rememberSaveable { mutableIntStateOf(0) }
    var savedAnchorId by rememberSaveable { mutableStateOf<String?>(null) }
    var savedJumpHeaderLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var savedNavigationSpacePx by rememberSaveable { mutableIntStateOf(0) }
    var previousQuery by remember { mutableStateOf(query) }
    var observedResetToken by rememberSaveable { mutableIntStateOf(homeResetToken) }
    val updateQuery: (String) -> Unit = { value ->
        // Capture before changing the list: LazyColumn can clamp its index during the same frame.
        if (query.isBlank() && value.isNotBlank()) {
            savedAnchorIndex = listState.firstVisibleItemIndex
            savedAnchorOffset = listState.firstVisibleItemScrollOffset
            savedAnchorId = anchorableEntries.getOrNull(savedAnchorIndex - 1)?.id
            savedJumpHeaderLabel = jumpHeaderLabel
            savedNavigationSpacePx = navigationSpacePx
        }
        if (value.isNotBlank()) listState.requestScrollToItem(0)
        if (searchQuery == null) localQuery = value else onSearchQueryChange?.invoke(value)
    }
    LaunchedEffect(query) {
        val oldQuery = previousQuery
        if (query != oldQuery) {
            previousQuery = query
            if (oldQuery.isBlank() && query.isNotBlank()) {
                if (savedAnchorIndex < 0) {
                    savedAnchorIndex = listState.firstVisibleItemIndex
                    savedAnchorOffset = listState.firstVisibleItemScrollOffset
                    savedAnchorId = anchorableEntries.getOrNull(savedAnchorIndex - 1)?.id
                    savedJumpHeaderLabel = jumpHeaderLabel
                    savedNavigationSpacePx = navigationSpacePx
                }
                reachability.reset()
                navigationSpacePx = 0
                jumpHeaderLabel = null
                listState.requestScrollToItem(0)
            } else if (oldQuery.isNotBlank() && query.isBlank()) {
                jumpHeaderLabel = savedJumpHeaderLabel?.takeIf { label -> entries.any { it is HomeListEntry.Header && it.navigationKey == label } }
                navigationSpacePx = if (jumpHeaderLabel != null) savedNavigationSpacePx else 0
                androidx.compose.runtime.withFrameNanos { }
                val currentIndex = entries.indexOfFirst { it.id == savedAnchorId }
                if (currentIndex >= 0) listState.requestScrollToItem(currentIndex + 1, savedAnchorOffset)
                else if (savedAnchorIndex >= 0) listState.requestScrollToItem(savedAnchorIndex.coerceAtMost(entries.lastIndex + 1), savedAnchorOffset)
                else listState.requestScrollToItem(0)
                savedAnchorIndex = -1
                savedAnchorId = null
                savedJumpHeaderLabel = null
                savedNavigationSpacePx = 0
            } else if (query.isNotBlank()) listState.requestScrollToItem(0)
        }
    }
    LaunchedEffect(homeResetToken) {
        if (homeResetToken != observedResetToken) {
            observedResetToken = homeResetToken
            updateQuery("")
            savedAnchorIndex = -1
            savedAnchorId = null
            savedJumpHeaderLabel = null
            savedNavigationSpacePx = 0
            previousQuery = ""
            reachability.reset()
            navigationSpacePx = 0
            jumpHeaderLabel = null
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(jump) {
        val target = jump ?: return@LaunchedEffect
        val index = headerIndices[target.first] ?: return@LaunchedEffect
        reachability.reset()
        navigationSpacePx = (viewportHeight / 3 - with(density) { 28.dp.roundToPx() }).coerceAtLeast(0)
        jumpHeaderLabel = target.first
        // Let the selected header and trailing space enter layout before targeting a
        // late section; otherwise a short final section can clamp the jump early.
        withFrameNanos { }
        listState.scrollToItem(index)
    }
    var editing by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun endEditing() { editing = false; onEditingChange(false); keyboard?.hide(); focusManager.clearFocus() }
    BackHandler(enabled = editing) { endEditing() }

    Box(Modifier.fillMaxSize().background(if (wallpaperMode) Color.Transparent else MaterialTheme.colorScheme.background)) {
        if (wallpaperMode && drawWallpaperScrim) WallpaperHomeScrim(wallpaperDimmingPercent, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding().statusBarsPadding()) {
            Spacer(Modifier.height(listTopInset))
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewportHeight = it.height; reachability.setViewport(it.height) }) {
                HomeAppList(
                    entries = entries,
                    listState = listState,
                    reachability = reachability,
                    navigationSpacePx = navigationSpacePx,
                    jumpHeaderLabel = jumpHeaderLabel,
                    viewportHeightPx = viewportHeight,
                    pausedProfiles = pausedProfiles,
                    showIcons = showAppIcons,
                    wallpaperMode = wallpaperMode,
                    pinnedKeys = pinnedAppKeys,
                    onPinApp = onPinApp,
                    onAppInfo = onAppInfo,
                    onUninstallApp = onUninstallApp,
                    onRenameApp = onRenameApp,
                    onHideApp = onHideApp,
                    onOrganizeApp = onOrganizeApp,
                    onOpenFolder = onOpenFolder,
                    onEditFolder = onEditFolder,
                    onFolderPress = onFolderPress,
                    onFolderBounds = onFolderBounds,
                    onAddCategoryApps = onAddCategoryApps,
                    onStartCategorySorter = onStartCategorySorter,
                    appRowContent = appRowContent,
                    onLaunch = { endEditing(); onLaunch(it) },
                    onRefresh = onRefresh,
                    onMakeDefault = onMakeDefault,
                    showMakeDefault = showMakeDefault,
                    catalogError = catalog.lastError,
                    isRefreshing = catalog.isRefreshing && normalApps.isNotEmpty(),
                    onOpenFeaturedSettings = onOpenFeaturedSettings,
                    featuredNeedsPermission = featuredNeedsPermission,
                    onOpenFeaturedManagement = onOpenFeaturedManagement,
                    featuredMessage = featuredMessage,
                    iconForApp = iconForApp,
                    enableReachability = enableReachability,
                    bottomPadding = if (showChrome) 12.dp else 108.dp,
                )
                if (showAlphabetRail && useSections && alphabet.isNotEmpty()) AlphabetRail(
                    alphabet = alphabet,
                    populated = headerIndices.keys + if ("Folders" in headerIndices) setOf("◇") else emptySet(),
                    onSelect = { label -> jumpSerial += 1; jump = (if (label == "◇") "Folders" else label) to jumpSerial },
                    wallpaperMode = wallpaperMode,
                    modifier = Modifier.align(AbsoluteAlignment.BottomRight),
                )
            }
            if (showChrome) Box(Modifier.fillMaxWidth().background(if (wallpaperMode) Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.75f))) else Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)))) {
                HomeBottomControls(
                    query = query,
                    onQueryChange = updateQuery,
                    focusRequester = focusRequester,
                    onEditingChange = { editing = it; onEditingChange(it) },
                    onSearchAction = { endEditing() },
                    onOpenSettings = { endEditing(); onOpenSettings() },
                    wallpaperMode = wallpaperMode,
                )
            }
        }
    }
}
