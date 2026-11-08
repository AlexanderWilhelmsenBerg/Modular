package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.organization.HomePageId
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private fun HomePageId.savedKey(): String = when (this) {
    HomePageId.Home -> "home"
    is HomePageId.Category -> "category:$id"
}

/** Category pages use this compact label; ordinary Home intentionally has none. */
@Composable
fun CategoryPageHeading(name: String, modifier: Modifier = Modifier) {
    Text(
        name,
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).testTag("category-page-heading"),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
fun CategoryPageEmptyState(
    name: String,
    onAddApps: () -> Unit,
    onStartMegaSorter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("No apps in $name yet", style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onAddApps, modifier = Modifier.testTag("category-add-apps")) { Text("Add apps") }
        OutlinedButton(onClick = onStartMegaSorter, modifier = Modifier.testTag("category-start-sorter")) { Text("Start Mega Sorter") }
    }
}

/** One physical left-to-right page sequence. Its content slot can reuse Home's list and launch path. */
@Composable
fun LauncherPages(
    snapshot: OrganizationSnapshot,
    gesturesEnabled: Boolean,
    homeResetToken: Int,
    renderPage: @Composable (HomePageId) -> Unit,
    modifier: Modifier = Modifier,
    initialPageId: HomePageId = HomePageId.Home,
    pageStateHolder: SaveableStateHolder = rememberSaveableStateHolder(),
    onPageChanged: (HomePageId) -> Unit = {},
) {
    val pages = snapshot.visiblePageOrder()
    val keys = pages.map { it.savedKey() }
    var selectedKey by rememberSaveable { mutableStateOf(initialPageId.savedKey()) }
    var observedResetToken by rememberSaveable { mutableStateOf(homeResetToken) }
    val initialPage = keys.indexOf(selectedKey).takeIf { it >= 0 } ?: keys.indexOf("home").coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pages.size })
    val contentDirection = LocalLayoutDirection.current
    val currentPages by rememberUpdatedState(pages)
    val currentPageCallback by rememberUpdatedState(onPageChanged)
    val scope = rememberCoroutineScope()

    // If the current page is disabled or deleted, navigate to Home. Reorders keep
    // the selected page by stable ID, rather than retaining a now-wrong index.
    LaunchedEffect(keys, homeResetToken) {
        val reset = observedResetToken != homeResetToken
        observedResetToken = homeResetToken
        val targetKey = if (reset || selectedKey !in keys) "home" else selectedKey
        val target = keys.indexOf(targetKey).coerceAtLeast(0)
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
        selectedKey = targetKey
        pages.getOrNull(target)?.let(currentPageCallback)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { index ->
            currentPages.getOrNull(index)?.let { page ->
                selectedKey = page.savedKey()
                currentPageCallback(page)
            }
        }
    }
    BackHandler(enabled = gesturesEnabled && pages.getOrNull(pagerState.currentPage) != HomePageId.Home) {
        scope.launch {
            pagerState.animateScrollToPage(keys.indexOf("home"))
            selectedKey = "home"
        }
    }
    // Compose's pager honors layout direction. Saved positions are physical, so
    // give only the pager an LTR coordinate system and restore content direction.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        HorizontalPager(
            state = pagerState,
            key = { keys[it] },
            userScrollEnabled = gesturesEnabled && pages.size > 1,
            modifier = modifier.fillMaxSize().testTag("launcher-pages"),
        ) { index ->
            val page = pages[index]
            pageStateHolder.SaveableStateProvider(page.savedKey()) {
                CompositionLocalProvider(LocalLayoutDirection provides contentDirection) {
                    Box(Modifier.fillMaxSize().testTag("launcher-page:${page.savedKey()}")) {
                        renderPage(page)
                    }
                }
            }
        }
    }
}
