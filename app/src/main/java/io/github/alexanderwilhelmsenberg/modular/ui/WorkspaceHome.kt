package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.apps.rememberAppIcon
import io.github.alexanderwilhelmsenberg.modular.organization.FolderDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.HomePinRef
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePreferences
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceSection
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import java.util.Date

/** Scrollable Home and widget-page content. The parent owns the shared rail and navigation. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorkspaceHome(
    pageId: String,
    pageTitle: String?,
    preferences: WorkspacePreferences,
    widgets: List<WorkspaceWidget>,
    widgetContent: @Composable (WorkspaceWidget) -> Unit,
    featuredApps: List<LaunchableApp>?,
    featuredFolders: List<FolderDefinition>,
    featuredMessage: String?,
    featuredNeedsPermission: Boolean,
    onOpenFeaturedSettings: (() -> Unit)?,
    onOpenFeaturedManagement: (() -> Unit)?,
    pinOrder: List<HomePinRef>,
    listeningApps: List<LaunchableApp>,
    pinnedKeys: Set<AppKey>,
    listState: LazyListState,
    wallpaperMode: Boolean,
    showIcons: Boolean,
    onLaunch: (LaunchableApp) -> Unit,
    onPinApp: ((LaunchableApp, Boolean) -> Unit)?,
    onAppInfo: ((LaunchableApp) -> Unit)?,
    onUninstallApp: ((LaunchableApp) -> Unit)?,
    onRenameApp: ((LaunchableApp) -> Unit)?,
    onHideApp: ((LaunchableApp) -> Unit)?,
    onOrganizeApp: ((LaunchableApp) -> Unit)?,
    onOpenFolder: (FolderDefinition) -> Unit,
    onEditFolder: (FolderDefinition) -> Unit,
    onFolderPress: (Boolean) -> Unit,
    onFolderBounds: (String, androidx.compose.ui.unit.IntRect) -> Unit,
    onAddWidget: () -> Unit,
    onEditWorkspace: () -> Unit,
    starJumpSerial: Int = 0,
    clockActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val mainHome = pageId == "home"
    val foreground = if (wallpaperMode) Color.White else MaterialTheme.colorScheme.onSurface
    val pageWidgets = remember(widgets, pageId) { widgets.filter { it.pageId == pageId } }
    val featured = remember(featuredApps, featuredFolders, pinOrder, featuredMessage, featuredNeedsPermission) {
        featuredHomeEntries(featuredApps, featuredFolders, pinOrder,
            showStatus = !featuredMessage.isNullOrBlank() || featuredNeedsPermission)
    }
    val sectionOrder = if (mainHome) preferences.sectionOrder.distinct() else
        listOf(WorkspaceSection.CLOCK, WorkspaceSection.WIDGETS)
    LaunchedEffect(starJumpSerial) {
        if (mainHome && starJumpSerial > 0) {
            var index = 1 // Empty launcher-owned wallpaper area before the sections.
            for (section in sectionOrder) {
                if (section == WorkspaceSection.MOST_USED) break
                when (section) {
                    WorkspaceSection.CLOCK -> if (preferences.showClock || preferences.showDate || preferences.showBattery) index++
                    WorkspaceSection.WIDGETS -> index += pageWidgets.size
                    WorkspaceSection.LISTENING -> if (listeningApps.isNotEmpty()) index += listeningApps.size + 1
                    WorkspaceSection.MOST_USED -> Unit
                }
            }
            listState.scrollToItem(index)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.testTag("workspace-home"),
        contentPadding = PaddingValues(top = 72.dp, bottom = 124.dp),
    ) {
        item(key = "workspace-empty-top") {
            Box(Modifier.fillMaxWidth().height(52.dp)
                .combinedClickable(onClick = {}, onLongClick = onEditWorkspace)
                .testTag("workspace-empty-edit-space"))
        }
        if (pageTitle != null) item(key = "page-heading") {
            Text(pageTitle, Modifier.fillMaxWidth().padding(start = 24.dp, end = 64.dp, bottom = 18.dp),
                style = MaterialTheme.typography.titleLarge, color = foreground)
        }
        sectionOrder.forEach { section ->
            when (section) {
                WorkspaceSection.CLOCK -> if (preferences.showClock || preferences.showDate || preferences.showBattery) {
                    item(key = "workspace-clock") {
                        Box(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onEditWorkspace)) {
                            WorkspaceClock(preferences, foreground, clockActive)
                        }
                    }
                }
                WorkspaceSection.WIDGETS -> {
                    items(pageWidgets, key = { "widget:${it.appWidgetId}" }) { widget ->
                        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 52.dp, bottom = 12.dp)) {
                            widgetContent(widget)
                        }
                    }
                }
                WorkspaceSection.MOST_USED -> if (mainHome) {
                    items(featured, key = { "workspace:${it.id}" }) { entry ->
                        when (entry) {
                            is HomeListEntry.Header -> Text(
                                if (entry.label == "★") "Most Used" else entry.label,
                                Modifier.fillMaxWidth().padding(start = 24.dp, top = 16.dp, bottom = 8.dp),
                                color = foreground, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                            )
                            is HomeListEntry.App -> HomeAppRow(
                                app = entry.app, enabled = entry.app.enabled,
                                showIcon = showIcons, icon = if (showIcons) rememberAppIcon(entry.app) else null,
                                wallpaperMode = wallpaperMode, pinned = entry.app.key in pinnedKeys,
                                onPinApp = onPinApp, onAppInfo = onAppInfo, onUninstallApp = onUninstallApp,
                                onRenameApp = onRenameApp, onHideApp = onHideApp,
                                onOrganizeApp = onOrganizeApp, onClick = { onLaunch(entry.app) },
                            )
                            is HomeListEntry.Folder -> FolderShortcut(entry.folder, wallpaperMode,
                                onOpenFolder, onEditFolder, onFolderPress, onFolderBounds)
                            HomeListEntry.FeaturedEmpty -> Column(Modifier.fillMaxWidth().padding(start = 24.dp, bottom = 12.dp)) {
                                Text(featuredMessage ?: "Pin apps to show them here", color = foreground)
                                if (onOpenFeaturedSettings != null) Button(onClick = onOpenFeaturedSettings,
                                    modifier = Modifier.testTag("featured-settings")) { Text("Pin apps") }
                                if (featuredNeedsPermission && onOpenFeaturedManagement != null)
                                    Button(onClick = onOpenFeaturedManagement, modifier = Modifier.testTag("featured-permission")) { Text("Enable usage access") }
                            }
                            HomeListEntry.FeaturedStatus -> Column(Modifier.fillMaxWidth().padding(start = 24.dp, bottom = 12.dp)) {
                                Text(featuredMessage ?: "Enable usage access to see suggested apps", color = foreground)
                                if (onOpenFeaturedSettings != null) Button(onClick = onOpenFeaturedSettings,
                                    modifier = Modifier.testTag("featured-settings")) { Text("Settings") }
                                if (featuredNeedsPermission && onOpenFeaturedManagement != null)
                                    Button(onClick = onOpenFeaturedManagement, modifier = Modifier.testTag("featured-permission")) { Text("Enable usage access") }
                            }
                            else -> Unit
                        }
                    }
                }
                WorkspaceSection.LISTENING -> if (mainHome && listeningApps.isNotEmpty()) {
                    item(key = "listening-title") {
                        Text("Listening", Modifier.fillMaxWidth().padding(start = 24.dp, top = 16.dp, bottom = 8.dp),
                            color = foreground, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    }
                    items(listeningApps, key = { "listening:${it.key.stableId}" }) { app ->
                        HomeAppRow(app = app, enabled = app.enabled, showIcon = showIcons,
                            icon = if (showIcons) rememberAppIcon(app) else null,
                            wallpaperMode = wallpaperMode, pinned = app.key in pinnedKeys,
                            onPinApp = onPinApp, onAppInfo = onAppInfo, onUninstallApp = onUninstallApp,
                            onRenameApp = onRenameApp, onHideApp = onHideApp,
                            onOrganizeApp = onOrganizeApp, onClick = { onLaunch(app) })
                    }
                }
            }
        }
        item(key = "workspace-actions") {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 52.dp, top = 18.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onAddWidget, modifier = Modifier.testTag("workspace-add-widget")) { Text("Add widget") }
                    TextButton(onClick = onEditWorkspace, modifier = Modifier.testTag("workspace-edit")) { Text("Edit Home") }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceClock(preferences: WorkspacePreferences, foreground: Color, active: Boolean = true) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var battery by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(context, lifecycle, active, preferences.showBattery) {
        var registered = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                now = System.currentTimeMillis()
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    battery = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
                }
            }
        }
        fun updateRegistration() {
            val wanted = active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (wanted && !registered) {
                now = System.currentTimeMillis()
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    if (preferences.showBattery) addAction(Intent.ACTION_BATTERY_CHANGED)
                }
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                registered = true
            } else if (!wanted && registered) {
                context.unregisterReceiver(receiver)
                registered = false
            }
        }
        val observer = LifecycleEventObserver { _, _ -> updateRegistration() }
        lifecycle.addObserver(observer)
        updateRegistration()
        onDispose {
            lifecycle.removeObserver(observer)
            if (registered) context.unregisterReceiver(receiver)
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 64.dp, bottom = 24.dp)) {
        if (preferences.showClock) Text(DateFormat.getTimeFormat(context).format(Date(now)),
            color = foreground, fontSize = 50.sp, lineHeight = 58.sp, fontWeight = FontWeight.Light)
        if (preferences.showDate) Text(DateFormat.getLongDateFormat(context).format(Date(now)),
            color = foreground.copy(alpha = 0.86f), style = MaterialTheme.typography.titleMedium)
        if (preferences.showBattery && battery != null) Text("Battery $battery%",
            color = foreground.copy(alpha = 0.76f), style = MaterialTheme.typography.bodyMedium)
    }
}
