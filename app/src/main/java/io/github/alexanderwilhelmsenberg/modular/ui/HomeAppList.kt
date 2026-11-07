package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey

internal sealed interface HomeListEntry {
    val id: String
    data class Header(val label: String) : HomeListEntry { override val id = "header:$label" }
    data class App(val app: LaunchableApp, val featured: Boolean = false) : HomeListEntry {
        override val id = (if (featured) "featured:" else "app:") + app.key.stableId
    }
    data class Notice(override val id: String, val message: String) : HomeListEntry
    data object Error : HomeListEntry { override val id = "catalog-error" }
    data object Loading : HomeListEntry { override val id = "catalog-loading" }
    data object Empty : HomeListEntry { override val id = "catalog-empty" }
    data object NoResults : HomeListEntry { override val id = "search-no-results" }
    data object FeaturedEmpty : HomeListEntry { override val id = "featured-empty" }
    data object FeaturedStatus : HomeListEntry { override val id = "featured-status" }
    data object Actions : HomeListEntry { override val id = "home-actions" }
}

@Composable
internal fun HomeAppList(
    entries: List<HomeListEntry>,
    listState: LazyListState,
    reachability: HomeReachabilityState,
    navigationSpacePx: Int,
    jumpHeaderLabel: String?,
    viewportHeightPx: Int,
    pausedProfiles: Set<Long>,
    showIcons: Boolean,
    wallpaperMode: Boolean,
    pinnedKeys: Set<AppKey>,
    onPinApp: ((LaunchableApp, Boolean) -> Unit)?,
    onLaunch: (LaunchableApp) -> Unit,
    onRefresh: () -> Unit,
    onMakeDefault: () -> Unit,
    showMakeDefault: Boolean,
    catalogError: String?,
    isRefreshing: Boolean,
    onOpenFeaturedSettings: (() -> Unit)?,
    featuredNeedsPermission: Boolean,
    onOpenFeaturedManagement: (() -> Unit)?,
    featuredMessage: String?,
    iconForApp: @Composable (LaunchableApp) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val foreground = if (wallpaperMode) Color.White else MaterialTheme.colorScheme.onSurface
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("home-list").nestedScroll(remember(reachability, listState) { reachability.nestedScroll(listState) }),
        state = listState,
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item(key = "reachability-space") { Spacer(Modifier.height(with(androidx.compose.ui.platform.LocalDensity.current) { reachability.offsetPx.toDp() })) }
        items(entries, key = { it.id }) { entry ->
            when (entry) {
                is HomeListEntry.Header -> {
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(54.dp + with(androidx.compose.ui.platform.LocalDensity.current) { (if (entry.label == jumpHeaderLabel) navigationSpacePx else 0).toDp() }), contentAlignment = AbsoluteAlignment.BottomLeft) {
                        val start = (maxWidth * 0.126f).coerceAtLeast(16.dp)
                        Text(entry.label, Modifier.absoluteOffset(x = start).padding(bottom = 6.dp).testTag("section:${entry.label}"), color = foreground, fontSize = 22.sp, fontWeight = FontWeight.Medium, style = if (wallpaperMode) TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f), 4f)) else TextStyle.Default)
                    }
                }
                is HomeListEntry.App -> {
                    val app = entry.app
                    val quiet = app.key.profileSerial in pausedProfiles
                    val icon = if (showIcons) iconForApp(app) else null
                    HomeAppRow(
                        app = app,
                        enabled = app.enabled && !quiet,
                        unavailabilityReason = if (quiet) "Profile paused" else null,
                        showIcon = showIcons,
                        icon = icon,
                        wallpaperMode = wallpaperMode,
                        pinned = app.key in pinnedKeys,
                        onPinApp = onPinApp,
                        onClick = { onLaunch(app) },
                    )
                }
                is HomeListEntry.Notice -> Text(entry.message, Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = foreground.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
                HomeListEntry.Error -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Apps could not be loaded", color = foreground, style = MaterialTheme.typography.titleMedium)
                    Text(catalogError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRefresh, modifier = Modifier.testTag("home-retry").semantics { contentDescription = "Retry loading apps" }) { Text("Retry") }
                }
                HomeListEntry.Loading -> Column(Modifier.fillMaxWidth().padding(24.dp)) { CircularProgressIndicator(); Text("Loading apps…", color = foreground) }
                HomeListEntry.Empty -> Text("No apps available", Modifier.fillMaxWidth().padding(24.dp), color = foreground)
                HomeListEntry.NoResults -> Text("No matching apps", Modifier.fillMaxWidth().padding(24.dp), color = foreground)
                HomeListEntry.FeaturedEmpty -> Column(Modifier.fillMaxWidth().padding(start = 52.dp, end = 56.dp, top = 8.dp, bottom = 20.dp)) {
                    Text(featuredMessage ?: "Pin apps to show them here", color = foreground)
                    if (onOpenFeaturedSettings != null) Button(onClick = onOpenFeaturedSettings, modifier = Modifier.testTag("featured-settings")) { Text("Pin apps") }
                    if (featuredNeedsPermission && onOpenFeaturedManagement != null) Button(onClick = onOpenFeaturedManagement, modifier = Modifier.testTag("featured-permission")) { Text("Enable usage access") }
                }
                HomeListEntry.FeaturedStatus -> Column(Modifier.fillMaxWidth().padding(start = 52.dp, end = 56.dp, top = 8.dp, bottom = 16.dp)) {
                    Text(featuredMessage ?: "Enable usage access to see suggested apps", color = foreground)
                    if (onOpenFeaturedSettings != null) Button(onClick = onOpenFeaturedSettings, modifier = Modifier.testTag("featured-settings")) { Text("Add-on settings") }
                    if (featuredNeedsPermission && onOpenFeaturedManagement != null) Button(onClick = onOpenFeaturedManagement, modifier = Modifier.testTag("featured-permission")) { Text("Enable usage access") }
                }
                HomeListEntry.Actions -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (isRefreshing) Text("Updating apps…", color = foreground, style = MaterialTheme.typography.bodySmall)
                    if (showMakeDefault) Button(onClick = onMakeDefault, modifier = Modifier.fillMaxWidth().testTag("home-make-default").semantics { contentDescription = "Make Modular the default launcher" }) { Text("Make default") }
                }
            }
        }
        // Trailing space allows the last section's header to reach the upper third during a rail jump.
        item(key = "navigation-tail-space") { Spacer(Modifier.height(with(androidx.compose.ui.platform.LocalDensity.current) { (if (navigationSpacePx > 0) viewportHeightPx * 0.67f else 16f).toDp() })) }
    }
}
