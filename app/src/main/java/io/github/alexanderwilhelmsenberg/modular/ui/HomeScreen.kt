package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.apps.rememberAppIcon
import io.github.alexanderwilhelmsenberg.modular.appearance.WallpaperHomeScrim

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
    alphabeticalSections: Boolean = true,
    homeResetToken: Int = 0,
    iconForApp: @Composable (LaunchableApp) -> ImageBitmap? = { rememberAppIcon(it) },
) {
    val locale = LocalConfiguration.current.locales[0]
    val normalApps = remember(catalog.apps, orderedApps, locale) { homeOrderedApps(catalog.apps, orderedApps, locale) }
    val alphabeticalApps = remember(catalog.apps, locale) { homeOrderedApps(catalog.apps, null, locale) }
    var localQuery by rememberSaveable { mutableStateOf("") }
    val query = searchQuery ?: localQuery
    val updateQuery: (String) -> Unit = { value -> if (searchQuery == null) localQuery = value else onSearchQueryChange?.invoke(value) }
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
    val entries = remember(sections, visibleApps, safeFeatured, featuredMessage, featuredNeedsPermission, searching, useSections, hasPausedProfileApps, catalog.isRefreshing, catalog.lastError, showMakeDefault) {
        buildList {
            if (hasPausedProfileApps) add(HomeListEntry.Notice("paused-profile-notice", "Some apps are unavailable while a profile is paused."))
            if (catalog.lastError != null) add(HomeListEntry.Error)
            if (catalog.isRefreshing && normalApps.isEmpty() && catalog.lastError == null) add(HomeListEntry.Loading)
            else if (normalApps.isEmpty() && catalog.lastError == null) add(HomeListEntry.Empty)
            else if (visibleApps.isEmpty() && searching && catalog.lastError == null) add(HomeListEntry.NoResults)
            if (!searching && safeFeatured != null) {
                add(HomeListEntry.Header("★"))
                if (safeFeatured.isEmpty()) add(HomeListEntry.FeaturedEmpty)
                else {
                    safeFeatured.forEach { add(HomeListEntry.App(it, featured = true)) }
                    if (!featuredMessage.isNullOrBlank() || featuredNeedsPermission) add(HomeListEntry.FeaturedStatus)
                }
            }
            if (useSections) sections.forEach { section ->
                add(HomeListEntry.Header(section.label))
                section.apps.forEach { add(HomeListEntry.App(it)) }
            } else visibleApps.forEach { add(HomeListEntry.App(it)) }
            add(HomeListEntry.Actions)
        }
    }
    var anchorableEntries by remember { mutableStateOf(entries) }
    SideEffect { if (!searching) anchorableEntries = entries }
    val headerIndices = remember(entries) { entries.mapIndexedNotNull { i, entry -> (entry as? HomeListEntry.Header)?.let { it.label to i + 1 } }.toMap() }
    val alphabet = remember(sectionBuilder, safeFeatured) {
        if (safeFeatured != null) listOf("★") + sectionBuilder.alphabet else sectionBuilder.alphabet
    }
    val reachability = rememberSaveable(saver = Saver(save = { it.offsetPx }, restore = { HomeReachabilityState(it) })) { HomeReachabilityState() }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var navigationSpacePx by rememberSaveable { mutableIntStateOf(0) }
    var jump by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var jumpSerial by remember { mutableIntStateOf(0) }
    var savedAnchorIndex by rememberSaveable { mutableIntStateOf(-1) }
    var savedAnchorOffset by rememberSaveable { mutableIntStateOf(0) }
    var savedAnchorId by rememberSaveable { mutableStateOf<String?>(null) }
    var previousQuery by remember { mutableStateOf(query) }
    var observedResetToken by rememberSaveable { mutableIntStateOf(homeResetToken) }
    LaunchedEffect(query) {
        if (query != previousQuery) {
            if (previousQuery.isBlank() && query.isNotBlank()) {
                savedAnchorIndex = listState.firstVisibleItemIndex
                savedAnchorOffset = listState.firstVisibleItemScrollOffset
                savedAnchorId = anchorableEntries.getOrNull(savedAnchorIndex - 1)?.id
                reachability.reset()
                navigationSpacePx = 0
                listState.scrollToItem(0)
            } else if (previousQuery.isNotBlank() && query.isBlank()) {
                val currentIndex = entries.indexOfFirst { it.id == savedAnchorId }
                if (currentIndex >= 0) listState.scrollToItem(currentIndex + 1, savedAnchorOffset)
                else if (savedAnchorIndex >= 0) listState.scrollToItem(savedAnchorIndex.coerceAtMost(entries.lastIndex + 1), savedAnchorOffset)
                else listState.scrollToItem(0)
                savedAnchorIndex = -1
                savedAnchorId = null
            } else if (query.isNotBlank()) listState.scrollToItem(0)
            previousQuery = query
        }
    }
    LaunchedEffect(homeResetToken) {
        if (homeResetToken != observedResetToken) {
            observedResetToken = homeResetToken
            updateQuery("")
            savedAnchorIndex = -1
            savedAnchorId = null
            previousQuery = ""
            reachability.reset()
            navigationSpacePx = 0
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(jump) {
        val target = jump ?: return@LaunchedEffect
        val index = headerIndices[target.first] ?: return@LaunchedEffect
        reachability.reset()
        navigationSpacePx = viewportHeight / 3
        listState.scrollToItem(index, -(viewportHeight / 3))
    }
    var editing by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun endEditing() { editing = false; keyboard?.hide(); focusManager.clearFocus() }
    BackHandler(enabled = editing) { endEditing() }

    Box(Modifier.fillMaxSize().background(if (wallpaperMode) Color.Transparent else MaterialTheme.colorScheme.background).imePadding().navigationBarsPadding().statusBarsPadding()) {
        if (wallpaperMode) WallpaperHomeScrim(wallpaperDimmingPercent, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewportHeight = it.height; reachability.setViewport(it.height) }) {
                HomeAppList(
                    entries = entries,
                    listState = listState,
                    reachability = reachability,
                    navigationSpacePx = navigationSpacePx,
                    viewportHeightPx = viewportHeight,
                    pausedProfiles = pausedProfiles,
                    showIcons = showAppIcons,
                    wallpaperMode = wallpaperMode,
                    pinnedKeys = pinnedAppKeys,
                    onPinApp = onPinApp.takeIf { safeFeatured != null },
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
                )
                if (useSections && normalApps.isNotEmpty()) AlphabetRail(
                    alphabet = alphabet,
                    populated = headerIndices.keys,
                    onSelect = { label -> jumpSerial += 1; jump = label to jumpSerial },
                    wallpaperMode = wallpaperMode,
                    modifier = Modifier.align(AbsoluteAlignment.CenterRight),
                )
            }
            Box(Modifier.fillMaxWidth().background(if (wallpaperMode) Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.75f))) else Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)))) {
                HomeBottomControls(
                    query = query,
                    onQueryChange = updateQuery,
                    focusRequester = focusRequester,
                    onEditingChange = { editing = it },
                    onSearchAction = { endEditing() },
                    onOpenSettings = { endEditing(); onOpenSettings() },
                )
            }
        }
    }
}
