package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import io.github.alexanderwilhelmsenberg.modular.workspace.calculateWidgetAreaGeometry
import io.github.alexanderwilhelmsenberg.modular.workspace.WidgetGridBand
import io.github.alexanderwilhelmsenberg.modular.workspace.groupWidgetGridBands
import io.github.alexanderwilhelmsenberg.modular.workspace.packWidgetGrid
import io.github.alexanderwilhelmsenberg.modular.widgets.WidgetLayoutLimits
import java.util.Date
import kotlin.math.roundToInt

/** Scrollable Home and widget-page content. The parent owns the shared rail and navigation. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorkspaceHome(
    pageId: String,
    pageTitle: String?,
    preferences: WorkspacePreferences,
    widgets: List<WorkspaceWidget>,
    widgetContent: @Composable (WorkspaceWidget, () -> Unit) -> Unit,
    canFitHalfWidget: (WorkspaceWidget, Int) -> Boolean = { _, _ -> true },
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
    onOpenWorkspaceAdvanced: () -> Unit = onEditWorkspace,
    widgetLayoutLimits: (WorkspaceWidget) -> WidgetLayoutLimits? = { null },
    onSaveWidgetGrid: suspend (Int, Int, Int, Int, Int) -> Result<Unit> = { _, _, _, _, _ -> Result.success(Unit) },
    onEditingChange: (Boolean) -> Unit = {},
    pageActive: Boolean = true,
    starJumpSerial: Int = 0,
    clockActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val mainHome = pageId == "home"
    val foreground = if (wallpaperMode) Color.White else MaterialTheme.colorScheme.onSurface
    val pageWidgets = remember(widgets, pageId) { widgets.filter { it.pageId == pageId } }
    var editMode by rememberSaveable(pageId) { mutableStateOf(false) }
    var selectedWidgetId by rememberSaveable(pageId) { mutableStateOf<Int?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    DisposableEffect(pageId, pageActive, editMode) {
        if (pageActive) onEditingChange(editMode)
        onDispose { if (pageActive && editMode) onEditingChange(false) }
    }
    BackHandler(editMode) { editMode = false; selectedWidgetId = null; onEditingChange(false) }
    fun startEditing(id: Int? = null) {
        menuOpen = false
        selectedWidgetId = id
        editMode = true
        onEditingChange(true)
    }
    val featured = remember(featuredApps, featuredFolders, pinOrder, featuredMessage, featuredNeedsPermission) {
        featuredHomeEntries(featuredApps, featuredFolders, pinOrder,
            showStatus = !featuredMessage.isNullOrBlank() || featuredNeedsPermission)
    }
    val sectionOrder = if (mainHome) preferences.sectionOrder.distinct().let { order ->
        if (editMode && WorkspaceSection.WIDGETS !in order) listOf(WorkspaceSection.WIDGETS) + order else order
    } else listOf(WorkspaceSection.CLOCK, WorkspaceSection.WIDGETS)
    BoxWithConstraints(modifier) {
    // The geometry is measured from the physical left edge. The alphabet rail is
    // reserved on the physical right even when Compose uses RTL layout direction.
    val geometry = calculateWidgetAreaGeometry(maxWidth.value.roundToInt(), preferences)
    val gridLayout = packWidgetGrid(pageWidgets, preferences, geometry) { widget ->
        val limit = widgetLayoutLimits(widget)
        if (limit == null) 0 to 0 else limit.minContentWidthDp to limit.minContentHeightDp
    }
    // A connected band can be too tall for one Compose item. In that rare case,
    // stack complete cells in separate items: all hosts stay reachable and cannot overlap.
    val widgetBands = groupWidgetGridBands(gridLayout).flatMap { band ->
        if (band.heightDp > 16_384 && band.cells.size > 1)
            band.cells.sortedWith(compareBy({ it.topDp }, { it.leftDp })).map { cell ->
                WidgetGridBand(cell.topDp, cell.heightDp, listOf(cell))
            }
        else listOf(band)
    }.ifEmpty {
        if (editMode) listOf(WidgetGridBand(0, 0, emptyList())) else emptyList()
    }
    val hasWidgetGrid = pageWidgets.isNotEmpty() || editMode
    LaunchedEffect(starJumpSerial) {
        if (mainHome && starJumpSerial > 0) {
            var index = 1 // Empty launcher-owned wallpaper area before the sections.
            for (section in sectionOrder) {
                if (section == WorkspaceSection.MOST_USED) break
                when (section) {
                    WorkspaceSection.CLOCK -> if (preferences.showClock || preferences.showDate || preferences.showBattery) index++
                    WorkspaceSection.WIDGETS -> if (hasWidgetGrid)
                        index += widgetBands.size + 1 + (if (editMode) 1 else 0)
                    WorkspaceSection.LISTENING -> if (listeningApps.isNotEmpty()) index += listeningApps.size + 1
                    WorkspaceSection.MOST_USED -> Unit
                }
            }
            listState.scrollToItem(index)
        }
    }
    LazyColumn(
        state = listState,
        userScrollEnabled = true,
        modifier = Modifier.fillMaxSize().pointerInput(listState, editMode) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Rows and native widgets own their holds. Only whitespace outside
                // measured list items, including content padding, opens this menu.
                val info = listState.layoutInfo
                val inItem = info.visibleItemsInfo.any { item ->
                    val viewportTop = item.offset - info.viewportStartOffset
                    val y = down.position.y
                    y >= viewportTop && y < viewportTop + item.size
                }
                if (inItem || editMode) {
                    var pressed: Boolean
                    do {
                        pressed = awaitPointerEvent(PointerEventPass.Final).changes.any { it.pressed }
                    } while (pressed)
                } else {
                    awaitLongPressOrCancellation(down.id)?.let { held ->
                        held.consume()
                        menuOpen = true
                    }
                }
            }
        }.testTag("workspace-home"),
        contentPadding = PaddingValues(top = 72.dp, bottom = 124.dp),
    ) {
        item(key = "workspace-empty-top") {
            Box(Modifier.fillMaxWidth().height(52.dp)
                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
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
                        Box(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menuOpen = true })) {
                            WorkspaceClock(preferences, foreground, clockActive)
                        }
                    }
                }
                WorkspaceSection.WIDGETS -> {
                    if (editMode) stickyHeader(key = "workspace-widget-edit-header") {
                        Row(Modifier.fillMaxWidth()
                            .background(if (wallpaperMode) Color.Black.copy(alpha = 0.8f)
                                else MaterialTheme.colorScheme.surface)
                            .padding(start = 16.dp, end = 64.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Arrange widgets", color = foreground,
                                style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = {
                                editMode = false; selectedWidgetId = null; onEditingChange(false)
                            }, modifier = Modifier.testTag("workspace-widget-edit-done")) { Text("Done") }
                        }
                    }
                    items(widgetBands, key = { "widget-band:${it.topDp}:${it.cells.firstOrNull()?.widget?.appWidgetId}" }) { band ->
                        WorkspaceWidgetGrid(gridLayout, band, preferences, geometry, editMode,
                            selectedWidgetId, onSelect = { selectedWidgetId = it },
                            onLongPress = { startEditing(it) }, limits = widgetLayoutLimits,
                            onEmptyLongPress = { menuOpen = true },
                            onSave = onSaveWidgetGrid, widgetContent = widgetContent)
                    }
                    if (hasWidgetGrid) item(key = "workspace-widget-gap") { Spacer(Modifier.height(geometry.gapDp.dp)) }
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
                    TextButton(onClick = { startEditing() }, modifier = Modifier.testTag("workspace-edit")) { Text("Edit Home") }
                }
            }
        }
    }
    Box(Modifier.absoluteOffset(x = 16.dp, y = 96.dp)) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
            modifier = Modifier.testTag("workspace-home-menu")) {
            DropdownMenuItem(text = { Text("Add widget") }, onClick = {
                menuOpen = false; onAddWidget()
            }, modifier = Modifier.testTag("workspace-menu-add-widget"))
            DropdownMenuItem(text = { Text("Edit") }, onClick = { startEditing() },
                modifier = Modifier.testTag("workspace-menu-edit"))
            DropdownMenuItem(text = { Text("Advanced") }, onClick = {
                menuOpen = false; onOpenWorkspaceAdvanced()
            }, modifier = Modifier.testTag("workspace-menu-advanced"))
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
