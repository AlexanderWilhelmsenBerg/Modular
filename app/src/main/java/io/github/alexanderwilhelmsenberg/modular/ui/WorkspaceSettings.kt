package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.organization.HomePageId
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationLimits
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import io.github.alexanderwilhelmsenberg.modular.widgets.WidgetLayoutLimits
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePage
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePageId
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceSection
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import java.text.Collator
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Settings and Home edit entry for the single workspace page sequence. */
@Composable
fun WorkspaceSettingsUi(
    snapshot: OrganizationSnapshot,
    onUpdate: suspend (OrganizationBatch) -> Result<OrganizationSnapshot>,
    onAddWidget: ((String) -> Unit)? = null,
    onReconfigureWidget: ((Int) -> Unit)? = null,
    onResizeWidget: ((Int, Int) -> Unit)? = null,
    onRemoveWidget: ((Int) -> Unit)? = null,
    onMoveWidget: ((Int, String) -> Unit)? = null,
    onMoveWidgetBy: ((Int, Int) -> Unit)? = null,
    widgetLabel: (WorkspaceWidget) -> String = { "Unavailable widget" },
    canConfigureWidget: (WorkspaceWidget) -> Boolean = { false },
    canResizeWidget: (WorkspaceWidget) -> Boolean = { false },
    widgetLayoutLimits: (WorkspaceWidget) -> WidgetLayoutLimits? = { null },
    canFitHalfWidget: (WorkspaceWidget, Int) -> Boolean = { _, _ -> false },
    onSaveWidgetLayout: (suspend (Int, Int, Int, Int) -> Result<Unit>)? = null,
    onSaveWidgetGridLayout: (suspend (Int, Int, Int, Int, Int, Int) -> Result<Unit>)? = null,
    onOpenGridSettings: (() -> Unit)? = null,
    inline: Boolean = false,
    initialOpenRequest: Int = 0,
    initialPageId: String = WorkspacePageId.HOME,
    onOpenRequestConsumed: () -> Unit = {},
    showEntry: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val dialogScroll = rememberScrollState()
    var open by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var addPage by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var editWidgetId by remember { mutableStateOf<Int?>(null) }
    var editWidgetArea by remember { mutableStateOf(false) }
    var selectedPageId by rememberSaveable { mutableStateOf(WorkspacePageId.HOME) }
    LaunchedEffect(initialOpenRequest) {
        if (initialOpenRequest > 0) {
            selectedPageId = initialPageId
            open = true
            onOpenRequestConsumed()
        }
    }
    LaunchedEffect(error) { if (error != null) dialogScroll.animateScrollTo(0) }

    fun save(edit: OrganizationEdit, afterSuccess: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val result = runCatching {
                onUpdate(OrganizationBatch(listOf(edit), expectedRevision = snapshot.revision))
            }.getOrElse { Result.failure(it) }
            busy = false
            result.onSuccess { afterSuccess() }.onFailure {
                error = it.message ?: "Could not save Home screens. Try again."
            }
        }
    }

    if (showEntry) SettingsRow(
        title = "Home screens",
        description = "Arrange screens, widgets, and Home layout",
        icon = SettingsIcon.HOME,
        modifier = Modifier.testTag("settings-home-screens"),
        onClick = { open = true },
    )

    if (open || inline) {
        @Composable fun WorkspaceContent() {
            Column(
                Modifier.fillMaxWidth().then(if (inline) Modifier else Modifier.heightIn(max = 560.dp).verticalScroll(dialogScroll))
                    .testTag(if (inline) "workspace-settings-content" else "workspace-dialog-scroll"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("Arrange screens from physical left to right. Home stays available.", style = MaterialTheme.typography.bodySmall)
                WorkspacePageTiles(snapshot, !busy, onReorder = { save(OrganizationEdit.SetPageOrder(it)) })
                OutlinedButton(
                    onClick = { addPage = true }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("workspace-add-page"),
                ) { Text("Add widget screen") }
                Text("Screens", style = MaterialTheme.typography.titleMedium)
                snapshot.pageOrder.forEach { page ->
                    when (page) {
                        HomePageId.Home -> WorkspaceScreenRow("Home", "Permanent main screen", false, null, null)
                        is HomePageId.Category -> {
                            val category = snapshot.categories.firstOrNull { it.id == page.id }
                            if (category != null) WorkspaceScreenRow(
                                category.name, if (category.enabled && snapshot.categoriesEnabled) "Category page" else "Category page · off",
                                false, null, null,
                            )
                        }
                        is HomePageId.Widget -> {
                            val custom = snapshot.workspacePages.firstOrNull { it.id == page.id }
                            if (custom != null) WorkspaceScreenRow(
                                custom.name, if (custom.enabled) "Widget screen" else "Widget screen · off",
                                !busy, custom.enabled,
                                onToggle = { save(OrganizationEdit.UpdateWorkspacePage(custom.copy(enabled = it))) },
                                onRename = { renameId = custom.id },
                                onDelete = { deleteId = custom.id },
                            )
                        }
                    }
                }
                Text("Home layout", style = MaterialTheme.typography.titleMedium)
                val prefs = snapshot.workspacePreferences
                WorkspaceToggle("Clock", prefs.showClock, !busy) {
                    save(OrganizationEdit.SetWorkspacePreferences(prefs.copy(showClock = it)))
                }
                WorkspaceToggle("Date", prefs.showDate, !busy) {
                    save(OrganizationEdit.SetWorkspacePreferences(prefs.copy(showDate = it)))
                }
                WorkspaceToggle("Battery percentage", prefs.showBattery, !busy) {
                    save(OrganizationEdit.SetWorkspacePreferences(prefs.copy(showBattery = it)))
                }
                if (onOpenGridSettings != null) {
                    OutlinedButton(onClick = onOpenGridSettings, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("workspace-open-grid-settings")) {
                        Text("Home grid · ${prefs.gridColumnCount} columns · ${prefs.gridRowHeightDp} dp rows")
                    }
                } else {
                    OutlinedButton(onClick = { error = null; editWidgetArea = true }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("workspace-widget-area")) {
                        Text("Widget area: ${prefs.widgetAreaWidthPercent}% width · ${prefs.widgetMarginDp} dp edge · ${prefs.widgetGapDp} dp gap")
                    }
                }
                Text("Section order", style = MaterialTheme.typography.titleSmall)
                prefs.sectionOrder.forEachIndexed { index, section ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(section.label(), Modifier.weight(1f).padding(top = 12.dp), maxLines = 1)
                        TextButton(enabled = !busy && index > 0, onClick = {
                            save(OrganizationEdit.SetWorkspacePreferences(prefs.copy(
                                sectionOrder = prefs.sectionOrder.moved(index, index - 1))))
                        }) { Text("↑") }
                        TextButton(enabled = !busy && index < prefs.sectionOrder.lastIndex, onClick = {
                            save(OrganizationEdit.SetWorkspacePreferences(prefs.copy(
                                sectionOrder = prefs.sectionOrder.moved(index, index + 1))))
                        }) { Text("↓") }
                    }
                }
                Text("Widgets", style = MaterialTheme.typography.titleMedium)
                val widgetPages = listOf(WorkspacePageId.HOME to "Home") +
                    snapshot.workspacePages.map { WorkspacePageId.widget(it.id) to it.name }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    widgetPages.forEach { (id, name) ->
                        TextButton(onClick = { selectedPageId = id }, modifier = Modifier.testTag("widget-page:$id")) {
                            Text(if (selectedPageId == id) "• $name" else name)
                        }
                    }
                }
                val selectedPage = widgetPages.firstOrNull { it.first == selectedPageId } ?: widgetPages.first()
                onAddWidget?.let { add ->
                    OutlinedButton(onClick = { add(selectedPage.first) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("workspace-add-widget")) { Text("Add widget to ${selectedPage.second}") }
                }
                val widgets = snapshot.widgets.filter { it.pageId == selectedPage.first }
                if (widgets.isEmpty()) Text("No widgets on this screen.", style = MaterialTheme.typography.bodySmall)
                widgets.forEachIndexed { index, widget ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(widgetLabel(widget).ifBlank { "Unavailable widget" },
                            style = MaterialTheme.typography.bodyMedium)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(enabled = !busy && index > 0, onClick = {
                                if (onMoveWidgetBy != null) onMoveWidgetBy(widget.appWidgetId, -1)
                                else save(OrganizationEdit.SetWidgets(snapshot.widgets.swapped(
                                    widget.appWidgetId, widgets[index - 1].appWidgetId)))
                            }) { Text("↑") }
                            TextButton(enabled = !busy && index < widgets.lastIndex, onClick = {
                                if (onMoveWidgetBy != null) onMoveWidgetBy(widget.appWidgetId, 1)
                                else save(OrganizationEdit.SetWidgets(snapshot.widgets.swapped(
                                    widget.appWidgetId, widgets[index + 1].appWidgetId)))
                            }) { Text("↓") }
                            widgetPages.filter { it.first != widget.pageId }.forEach { (id, name) ->
                                TextButton(enabled = !busy, onClick = {
                                    if (onMoveWidget != null) onMoveWidget(widget.appWidgetId, id)
                                    else save(OrganizationEdit.SetWidgets(snapshot.widgets.map {
                                        if (it.appWidgetId == widget.appWidgetId) it.copy(pageId = id) else it
                                    }))
                                }) { Text("Move to $name", modifier = Modifier.widthIn(max = 220.dp),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            if ((onSaveWidgetGridLayout != null || onSaveWidgetLayout != null) && widgetLayoutLimits(widget) != null) {
                                TextButton(enabled = !busy, onClick = { editWidgetId = widget.appWidgetId },
                                    modifier = Modifier.testTag("workspace-widget-layout:${widget.appWidgetId}")) {
                                    Text("Size and layout")
                                }
                            } else onResizeWidget?.takeIf { canResizeWidget(widget) }?.let { resize ->
                                TextButton(enabled = !busy && widget.heightDp > OrganizationLimits.MIN_WIDGET_HEIGHT_DP,
                                    onClick = { resize(widget.appWidgetId, -48) }) { Text("Shorter") }
                                TextButton(enabled = !busy && widget.heightDp < OrganizationLimits.MAX_WIDGET_HEIGHT_DP,
                                    onClick = { resize(widget.appWidgetId, 48) }) { Text("Taller") }
                            }
                            onReconfigureWidget?.takeIf { canConfigureWidget(widget) }?.let { reconfigure ->
                                TextButton(enabled = !busy, onClick = { reconfigure(widget.appWidgetId) }) { Text("Configure") }
                            }
                            onRemoveWidget?.let { remove ->
                                TextButton(enabled = !busy, onClick = { remove(widget.appWidgetId) }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
        if (inline) WorkspaceContent() else AlertDialog(
            onDismissRequest = { if (!busy) open = false },
            title = { Text("Home screens") },
            text = { WorkspaceContent() },
            confirmButton = { TextButton(onClick = { open = false }, enabled = !busy) { Text("Done") } },
        )
    }

    if (addPage) WorkspacePageEditor(
        page = null, snapshot = snapshot, busy = busy, error = error,
        onDismiss = { addPage = false },
        onSave = { name, left ->
            val home = snapshot.pageOrder.indexOf(HomePageId.Home).coerceAtLeast(0)
            val position = if (left) home else home + 1
            save(OrganizationEdit.CreateWorkspacePage(WorkspacePage(UUID.randomUUID().toString(), name), position)) {
                addPage = false
                scope.launch { dialogScroll.animateScrollTo(0) }
            }
        },
    )
    snapshot.workspacePages.firstOrNull { it.id == renameId }?.let { page ->
        WorkspacePageEditor(page, snapshot, busy, error, onDismiss = { renameId = null },
            onSave = { name, _ -> save(OrganizationEdit.UpdateWorkspacePage(page.copy(name = name))) { renameId = null } })
    }
    snapshot.workspacePages.firstOrNull { it.id == deleteId }?.let { page ->
        val count = snapshot.widgets.count { it.pageId == WorkspacePageId.widget(page.id) }
        AlertDialog(
            onDismissRequest = { if (!busy) deleteId = null },
            title = { Text("Delete ${page.name}?") },
            text = { Text("This removes the screen and its $count widget${if (count == 1) "" else "s"}. Widget provider apps stay installed.") },
            confirmButton = { Button(enabled = !busy, onClick = {
                save(OrganizationEdit.DeleteWorkspacePage(page.id)) { deleteId = null }
            }) { Text("Delete screen") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleteId = null }) { Text("Cancel") } },
        )
    }
    if (editWidgetArea) WidgetAreaEditor(
        preferences = snapshot.workspacePreferences,
        busy = busy,
        error = error,
        onDismiss = { editWidgetArea = false },
        onSave = { preferences, onSuccess ->
            save(OrganizationEdit.SetWorkspacePreferences(preferences), onSuccess)
        },
    )
    snapshot.widgets.firstOrNull { it.appWidgetId == editWidgetId }?.let { widget ->
        val limits = widgetLayoutLimits(widget)
        val saveGrid = onSaveWidgetGridLayout
        if (limits != null && saveGrid != null) WidgetSizeEditor(
            widget = widget,
            preferences = snapshot.workspacePreferences,
            limits = limits,
            onDismiss = { editWidgetId = null },
            onSave = saveGrid,
            onSaved = { editWidgetId = null },
        )
    }
}

internal fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    if (from !in indices || to !in indices || from == to) this
    else toMutableList().apply { add(to, removeAt(from)) }

private fun List<WorkspaceWidget>.swapped(
    firstId: Int, secondId: Int,
): List<WorkspaceWidget> {
    val first = indexOfFirst { it.appWidgetId == firstId }
    val second = indexOfFirst { it.appWidgetId == secondId }
    if (first < 0 || second < 0) return this
    return toMutableList().apply { val item = this[first]; this[first] = this[second]; this[second] = item }
}

/** Home itself is fixed as an identity; other pages can cross it. */
internal fun movedWorkspacePage(order: List<HomePageId>, from: Int, to: Int): List<HomePageId> =
    if (order.getOrNull(from) == HomePageId.Home) order else order.moved(from, to)

@Composable
private fun WorkspacePageTiles(snapshot: OrganizationSnapshot, enabled: Boolean, onReorder: (List<HomePageId>) -> Unit) {
    val density = LocalDensity.current
    val step = with(density) { 100.dp.toPx() }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("workspace-page-order"),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            snapshot.pageOrder.forEachIndexed { index, page ->
                val label = when (page) {
                    HomePageId.Home -> "Home"
                    is HomePageId.Category -> snapshot.categories.firstOrNull { it.id == page.id }?.name ?: "Removed"
                    is HomePageId.Widget -> snapshot.workspacePages.firstOrNull { it.id == page.id }?.name ?: "Removed"
                }
                val active = when (page) {
                    HomePageId.Home -> true
                    is HomePageId.Category -> snapshot.categoriesEnabled && snapshot.categories.any { it.id == page.id && it.enabled }
                    is HomePageId.Widget -> snapshot.workspacePages.any { it.id == page.id && it.enabled }
                }
                var drag by remember(page) { mutableFloatStateOf(0f) }
                val dragModifier = if (enabled && page != HomePageId.Home) Modifier.pointerInput(snapshot.pageOrder, page) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { drag = 0f },
                        onDragEnd = {
                            val target = (index + (drag / step).roundToInt()).coerceIn(snapshot.pageOrder.indices)
                            drag = 0f
                            if (target != index) onReorder(movedWorkspacePage(snapshot.pageOrder, index, target))
                        },
                        onDragCancel = { drag = 0f },
                        onDrag = { change, amount -> change.consume(); drag += amount.x },
                    )
                } else Modifier
                OutlinedCard(
                    Modifier.width(92.dp).then(dragModifier).testTag("workspace-tile:${when (page) {
                        HomePageId.Home -> "home"
                        is HomePageId.Category -> "category:${page.id}"
                        is HomePageId.Widget -> "widget:${page.id}"
                    }}"),
                    colors = CardDefaults.outlinedCardColors(containerColor =
                        if (drag != 0f || page == HomePageId.Home) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface),
                ) {
                    Text(label, Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 12.dp),
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = when {
                            page == HomePageId.Home -> MaterialTheme.colorScheme.onPrimaryContainer
                            active -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        })
                }
            }
        }
    }
    Text("Long press and drag a screen, or use the move controls below.", style = MaterialTheme.typography.bodySmall)
    snapshot.pageOrder.forEachIndexed { index, page ->
        if (page == HomePageId.Home) return@forEachIndexed
        val name = when (page) {
            is HomePageId.Category -> snapshot.categories.firstOrNull { it.id == page.id }?.name
            is HomePageId.Widget -> snapshot.workspacePages.firstOrNull { it.id == page.id }?.name
            HomePageId.Home -> null
        } ?: return@forEachIndexed
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, Modifier.weight(1f).padding(top = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(enabled = enabled && index > 0, onClick = {
                onReorder(movedWorkspacePage(snapshot.pageOrder, index, index - 1))
            }) { Text("←") }
            TextButton(enabled = enabled && index < snapshot.pageOrder.lastIndex, onClick = {
                onReorder(movedWorkspacePage(snapshot.pageOrder, index, index + 1))
            }) { Text("→") }
        }
    }
}

@Composable
private fun WorkspaceScreenRow(
    title: String, description: String, editable: Boolean, checked: Boolean?,
    onToggle: ((Boolean) -> Unit)? = null, onRename: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            if (checked != null && onToggle != null) Switch(checked, onCheckedChange = onToggle, enabled = editable)
        }
        if (editable) Row {
            onRename?.let { TextButton(onClick = it) { Text("Rename") } }
            onDelete?.let { TextButton(onClick = it) { Text("Delete") } }
        }
    }
}

@Composable
private fun WorkspaceToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(top = 10.dp))
        Switch(checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun WorkspaceSection.label(): String = when (this) {
    WorkspaceSection.CLOCK -> "Clock and date"
    WorkspaceSection.WIDGETS -> "Widgets"
    WorkspaceSection.MOST_USED -> "Most Used"
    WorkspaceSection.LISTENING -> "Listening"
}

internal fun workspacePageNameError(name: String, snapshot: OrganizationSnapshot, ownId: String?): String? {
    val value = name.trim()
    if (value.isEmpty()) return "Enter a screen name."
    if (value.codePointCount(0, value.length) > 60) return "Use at most 60 characters."
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    val names = listOf("Home") + snapshot.categories.map { it.name } +
        snapshot.workspacePages.filter { it.id != ownId }.map { it.name }
    if (names.any { collator.compare(it, value) == 0 }) return "A screen already has this name."
    return null
}

@Composable
private fun WorkspacePageEditor(
    page: WorkspacePage?, snapshot: OrganizationSnapshot, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var name by remember(page?.id) { mutableStateOf(page?.name.orEmpty()) }
    var left by remember(page?.id) { mutableStateOf(false) }
    val validation = workspacePageNameError(name, snapshot, page?.id)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (page == null) "Add widget screen" else "Rename screen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, onValueChange = { name = it }, label = { Text("Name") },
                    singleLine = true, isError = validation != null && name.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().testTag("workspace-page-name"))
                validation?.let { if (name.isNotEmpty()) Text(it, color = MaterialTheme.colorScheme.error) }
                if (page == null) {
                    Text("Place beside Home", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { left = true }) { Text(if (left) "✓ Left" else "Left") }
                        TextButton(onClick = { left = false }) { Text(if (!left) "✓ Right" else "Right") }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(enabled = !busy && validation == null, onClick = {
            keyboard?.hide()
            onSave(name.trim(), left)
        }) { Text("Save") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
