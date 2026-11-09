package io.github.alexanderwilhelmsenberg.modular.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationLimits
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationStore
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import io.github.alexanderwilhelmsenberg.modular.workspace.calculateWidgetAreaGeometry
import io.github.alexanderwilhelmsenberg.modular.workspace.gridSpanHeightDp
import io.github.alexanderwilhelmsenberg.modular.workspace.gridSpanWidthDp
import io.github.alexanderwilhelmsenberg.modular.workspace.packWidgetGrid
import io.github.alexanderwilhelmsenberg.modular.workspace.resolveWidgetGridSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.min

internal const val HOST_ID = 0x4d4f44
private const val STAGE_EXPIRY_MS = 24L * 60 * 60 * 1000

/** Provider content limits in dp; saved widget padding is outside these bounds. */
data class WidgetLayoutLimits(
    val minContentWidthDp: Int,
    val minContentHeightDp: Int,
    val maxContentHeightDp: Int,
    val canResizeWidth: Boolean,
    val canResizeHeight: Boolean,
    val maxContentWidthDp: Int = Int.MAX_VALUE,
)

/** One instance per visible launcher Activity. Keep it mounted across workspace page changes. */
class WorkspaceWidgetController internal constructor(
    context: Context,
    private val store: OrganizationStore,
    private val scope: CoroutineScope,
) {
    private val context = context.applicationContext
    private val manager = AppWidgetManager.getInstance(context)
    private val host = object : AppWidgetHost(context, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo): AppWidgetHostView =
            EditableWidgetHostView(context)
    }
    private val journal = WidgetStageJournal(context)
    val currentHostToken: String = runCatching { workspaceHostToken(context) }.getOrDefault("")
    private var launchBind: ((Intent) -> Unit)? = null
    private var launchConfigure: ((Intent) -> Unit)? = null
    private var message: (String) -> Unit = {}
    private var visible = false
    private var started = false
    private var listening = false
    private var placements: List<WorkspaceWidget> = emptyList()
    private var finishingNewId: Int? = null
    var pickerPageId by mutableStateOf<String?>(null)
        private set
    var pendingRecovery by mutableStateOf(false)
        private set

    internal fun attach(bind: (Intent) -> Unit, configure: (Intent) -> Unit, onMessage: (String) -> Unit) {
        launchBind = bind
        launchConfigure = configure
        message = onMessage
    }

    internal fun updatePlacements(widgets: List<WorkspaceWidget>) {
        placements = widgets
    }

    internal fun setVisible(value: Boolean) { visible = value; updateListening() }
    internal fun setStarted(value: Boolean) { started = value; updateListening() }

    private fun updateListening() {
        val wanted = started && visible
        if (wanted == listening) return
        try {
            if (wanted) host.startListening() else host.stopListening()
            listening = wanted
        } catch (failure: RuntimeException) {
            message("Could not ${if (wanted) "start" else "stop"} widgets: ${failure.message.orEmpty()}")
        }
    }

    internal fun dispose() {
        started = false
        visible = false
        updateListening()
    }

    /** Reconcile only after the persisted snapshot has loaded. */
    fun reconcile() {
        scope.launch {
            if (store.error.value != null) return@launch
            if (currentHostToken.isEmpty()) return@launch
            // Read authoritative storage here: a just-committed addition can precede
            // the Compose snapshot reaching this call.
            val saved = store.currentSnapshot().widgets
            if (store.error.value != null) return@launch
            placements = saved
            runCatching {
                val stage = journal.read()
                val stale = stage != null && System.currentTimeMillis() - stage.createdAt > STAGE_EXPIRY_MS
                if (stale) {
                    if (stage!!.phase != WidgetStage.Phase.RECONFIGURE && saved.none { it.appWidgetId == stage.id })
                        deleteHostIdSafely(stage.id)
                    journal.clear()
                }
                // Only this installation's placements own host IDs. Restored records
                // stay in the model as placeholders without protecting stale IDs.
                val localIds = saved.filter { it.hostToken == currentHostToken }.map { it.appWidgetId }
                abandonedWidgetIds(host.appWidgetIds.toList(), localIds, journal.read())
                    .forEach(::deleteHostIdSafely)
            }.onFailure { message("Could not reconcile widgets: ${it.message.orEmpty()}") }
        }
    }

    fun providerInfo(widget: WorkspaceWidget): AppWidgetProviderInfo? =
        runCatching { manager.getAppWidgetInfo(widget.appWidgetId) }.getOrNull()?.takeIf {
            currentHostToken.isNotEmpty() && widget.hostToken == currentHostToken &&
            it.provider.flattenToString() == widget.provider
        }

    fun layoutLimits(widget: WorkspaceWidget): WidgetLayoutLimits? =
        providerInfo(widget)?.let(::layoutLimits)

    private fun layoutLimits(info: AppWidgetProviderInfo): WidgetLayoutLimits {
        val horizontal = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        val vertical = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        val minWidthPx = if (horizontal && info.minResizeWidth > 0) info.minResizeWidth else info.minWidth
        val minHeightPx = if (vertical && info.minResizeHeight > 0) info.minResizeHeight else info.minHeight
        val minimumHeight = pxToDp(minHeightPx.coerceAtLeast(0))
        val minimumWidth = pxToDp(minWidthPx.coerceAtLeast(0))
        val maxResizePx = if (vertical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            info.maxResizeHeight else 0
        val maxResizeWidthPx = if (horizontal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            info.maxResizeWidth else 0
        return WidgetLayoutLimits(
            minContentWidthDp = minimumWidth,
            minContentHeightDp = minimumHeight,
            maxContentHeightDp = (if (maxResizePx > 0) pxToDp(maxResizePx)
                else Int.MAX_VALUE).coerceAtLeast(minimumHeight),
            canResizeWidth = horizontal,
            canResizeHeight = vertical,
            maxContentWidthDp = if (maxResizeWidthPx > 0) pxToDp(maxResizeWidthPx)
                .coerceAtLeast(minimumWidth) else Int.MAX_VALUE,
        )
    }

    /** A saved half span falls back to full width when the current cell is too narrow. */
    fun canFitHalf(widget: WorkspaceWidget, cellWidthDp: Int): Boolean {
        val limits = layoutLimits(widget) ?: return false
        return cellWidthDp - 2 * widget.paddingDp >= limits.minContentWidthDp
    }

    internal fun providers(): List<AppWidgetProviderInfo> =
        runCatching { manager.installedProviders.orEmpty() }.getOrDefault(emptyList())
        .sortedWith(compareBy<AppWidgetProviderInfo> { info ->
            runCatching { info.provider.packageName.let { pkg ->
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
            } }.getOrDefault(info.provider.packageName).lowercase()
        }.thenBy { info -> runCatching { info.loadLabel(context.packageManager).orEmpty() }
            .getOrDefault(info.provider.className) })

    internal fun context(): Context = context
    internal fun hostView(widget: WorkspaceWidget): View = runCatching {
        host.createView(context, widget.appWidgetId, requireNotNull(providerInfo(widget)))
    }.getOrElse {
        TextView(context).apply { text = "Widget could not be displayed. Remove it and add it again." }
    }

    fun addWidget(pageId: String) {
        val stage = runCatching { journal.read() }.getOrElse {
            message("Could not read widget setup state")
            return
        }
        if (!mayStartWidgetFlow(stage, finishingNewId)) {
            pendingRecovery = true
            return
        }
        pickerPageId = pageId
    }
    fun dismissPicker() { pickerPageId = null }

    fun dismissPendingRecovery() { pendingRecovery = false }

    fun cancelPendingWidgetSetup() {
        if (finishingNewId != null) {
            message("Widget setup is finishing. Please wait a moment.")
            return
        }
        val stage = runCatching { journal.read() }.getOrNull()
        if (stage == null) {
            pendingRecovery = false
            return
        }
        if (runCatching { journal.clear() }.isFailure) {
            message("Could not cancel widget setup")
            return
        }
        if (stage.phase != WidgetStage.Phase.RECONFIGURE &&
            placements.none { it.appWidgetId == stage.id && it.hostToken == currentHostToken })
            deleteHostIdSafely(stage.id)
        pendingRecovery = false
    }

    fun chooseProvider(info: AppWidgetProviderInfo) {
        val page = pickerPageId ?: return
        pickerPageId = null
        if (currentHostToken.isEmpty()) {
            message("Widget host storage is unavailable")
            return
        }
        // A previous interrupted flow is reclaimed before allocating another host ID.
        val previous = runCatching { journal.read() }.getOrElse {
            message("Could not read interrupted widget setup")
            return
        }
        if (!mayStartWidgetFlow(previous, finishingNewId)) {
            pendingRecovery = true
            return
        }
        val id = runCatching { host.allocateAppWidgetId() }.getOrElse {
            message("Could not allocate widget ID: ${it.message.orEmpty()}")
            return
        }
        val provider = info.provider.flattenToString()
        try {
            journal.write(WidgetStage(id, provider, page, WidgetStage.Phase.BIND, System.currentTimeMillis()))
            if (manager.bindAppWidgetIdIfAllowed(id, info.provider)) onBound(id, info)
            else launchBind?.invoke(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            }) ?: cancelNew(id)
        } catch (failure: Exception) {
            cancelNew(id)
            message("Could not add widget: ${failure.message.orEmpty()}")
        }
    }

    internal fun bindResult(resultCode: Int, data: Intent?) {
        val stage = runCatching { journal.read() }.getOrNull()
            ?.takeIf { it.phase == WidgetStage.Phase.BIND } ?: return
        val returnedId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (resultCode == Activity.RESULT_OK && !resultMatchesStage(returnedId, stage)) {
            cancelNew(stage.id)
            return
        }
        val info = runCatching { manager.getAppWidgetInfo(stage.id) }.getOrNull()
        if (resultCode != Activity.RESULT_OK || info == null ||
            info.provider.flattenToString() != stage.provider) {
            cancelNew(stage.id)
            return
        }
        runCatching { onBound(stage.id, info) }.onFailure {
            cancelNew(stage.id)
            message("Could not finish widget binding: ${it.message.orEmpty()}")
        }
    }

    private fun onBound(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            val stage = journal.read() ?: return cancelNew(id)
            journal.write(stage.copy(phase = WidgetStage.Phase.CONFIGURE_NEW))
            launchConfiguration(id, info.provider)
        } else finishNew(id)
    }

    private fun launchConfiguration(id: Int, provider: ComponentName) {
        try {
            launchConfigure?.invoke(Intent(context, WidgetConfigurationActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(WidgetConfigurationActivity.EXTRA_PROVIDER, provider.flattenToString())
            }) ?: throw IllegalStateException("Widget configuration unavailable")
        } catch (failure: Exception) {
            if (journal.read()?.phase == WidgetStage.Phase.RECONFIGURE)
                runCatching { journal.clear() }.onFailure { message("Could not clear widget configuration") }
            else cancelNew(id)
            message("Could not configure widget: ${failure.message.orEmpty()}")
        }
    }

    internal fun configurationResult(resultCode: Int, data: Intent?) {
        val stage = runCatching { journal.read() }.getOrNull() ?: return
        val returnedId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (!resultMatchesStage(returnedId, stage)) return
        when (stage.phase) {
            WidgetStage.Phase.CONFIGURE_NEW -> if (resultCode == Activity.RESULT_OK) finishNew(stage.id) else cancelNew(stage.id)
            WidgetStage.Phase.RECONFIGURE -> runCatching { journal.clear() }.onFailure {
                message("Could not finish widget configuration")
            } // Existing placement survives cancellation.
            WidgetStage.Phase.BIND -> Unit
        }
    }

    private fun finishNew(id: Int) {
        val stage = runCatching { journal.read() }.getOrNull()?.takeIf { it.id == id } ?: return
        val info = runCatching { manager.getAppWidgetInfo(id) }.getOrNull()
        if (info == null || info.provider.flattenToString() != stage.provider) return cancelNew(id)
        if (finishingNewId == id) return
        finishingNewId = id
        scope.launch {
            try {
                val limits = layoutLimits(info)
                val preferences = store.currentSnapshot().workspacePreferences
                val geometry = calculateWidgetAreaGeometry(context.resources.configuration.screenWidthDp, preferences)
                val span = initialWidgetGridSpan(preferences, geometry,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.targetCellWidth else 0,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.targetCellHeight else 0,
                    pxToDp(info.minWidth), pxToDp(info.minHeight))
                val initialHeight = gridSpanHeightDp(span.rowSpan, preferences).coerceIn(
                    OrganizationLimits.MIN_WIDGET_HEIGHT_DP,
                    limits.maxContentHeightDp.coerceIn(OrganizationLimits.MIN_WIDGET_HEIGHT_DP,
                        OrganizationLimits.MAX_WIDGET_HEIGHT_DP))
                val result = edit { current ->
                    if (!resultMatchesStage(id, journal.read()) || current.any { it.appWidgetId == id }) current
                    else current + WorkspaceWidget(id, stage.pageId, stage.provider, initialHeight, currentHostToken,
                        columnSpan = span.columnSpan, rowSpan = span.rowSpan)
                }
                if (result) {
                    if (resultMatchesStage(id, journal.read())) runCatching { journal.clear() }.onFailure {
                        message("Could not finalize widget setup; it will be reconciled later")
                    }
                } else {
                    cancelNew(id)
                    message("Could not save widget placement")
                }
            } finally {
                finishingNewId = null
            }
        }
    }

    private fun cancelNew(id: Int) {
        runCatching { if (journal.read()?.id == id) journal.clear() }
            .onFailure { message("Could not clear staged widget") }
        deleteHostIdSafely(id)
    }

    private fun deleteHostIdSafely(id: Int) {
        runCatching { if (host.appWidgetIds.contains(id)) host.deleteAppWidgetId(id) }
            .onFailure { message("Could not release widget ID $id") }
    }

    fun reconfigureWidget(id: Int) {
        val stage = runCatching { journal.read() }.getOrElse {
            message("Could not read widget setup state")
            return
        }
        if (!mayStartWidgetFlow(stage, finishingNewId)) {
            pendingRecovery = true
            return
        }
        val widget = placements.firstOrNull { it.appWidgetId == id && it.hostToken == currentHostToken } ?: return
        val info = providerInfo(widget)?.takeIf { it.configure != null } ?: return
        try {
            journal.write(WidgetStage(id, widget.provider, widget.pageId,
                WidgetStage.Phase.RECONFIGURE, System.currentTimeMillis()))
            launchConfiguration(id, info.provider)
        } catch (failure: Exception) { message("Could not configure widget: ${failure.message.orEmpty()}") }
    }

    fun removeWidget(id: Int) {
        scope.launch {
            val owned = placements.any { it.appWidgetId == id && it.hostToken == currentHostToken }
            if (edit { widgets -> widgets.filterNot { it.appWidgetId == id } }) {
                if (owned) deleteHostIdSafely(id)
            }
            else message("Could not remove widget")
        }
    }

    fun moveWidget(id: Int, pageId: String) {
        scope.launch {
            if (!edit { widgets -> widgets.map { if (it.appWidgetId == id)
                it.copy(pageId = pageId, gridColumn = -1, gridRow = -1) else it } })
                message("Could not move widget")
        }
    }

    fun moveWidgetBy(id: Int, delta: Int) {
        scope.launch {
            if (!edit { widgets ->
                val from = widgets.indexOfFirst { it.appWidgetId == id }
                if (from < 0) widgets else {
                    val samePage = widgets.withIndex().filter { it.value.pageId == widgets[from].pageId }.map { it.index }
                    val rank = samePage.indexOf(from)
                    val target = samePage.getOrNull(rank + delta) ?: return@edit widgets
                    widgets.toMutableList().apply { add(target, removeAt(from)) }.map {
                        if (it.pageId == widgets[from].pageId) it.copy(gridColumn = -1, gridRow = -1) else it
                    }
                }
            }) message("Could not reorder widget")
        }
    }

    fun resizeWidget(id: Int, deltaDp: Int) {
        val widget = placements.firstOrNull { it.appWidgetId == id && it.hostToken == currentHostToken } ?: return
        val limits = layoutLimits(widget) ?: return
        if (!limits.canResizeHeight) return
        val min = limits.minContentHeightDp.coerceIn(
            OrganizationLimits.MIN_WIDGET_HEIGHT_DP, OrganizationLimits.MAX_WIDGET_HEIGHT_DP)
        val max = min(OrganizationLimits.MAX_WIDGET_HEIGHT_DP,
            limits.maxContentHeightDp).coerceAtLeast(min)
        val height = (widget.heightDp + deltaDp).coerceIn(min, max)
        scope.launch {
            saveWidgetLayout(id, height, widget.span, widget.paddingDp).onFailure {
                message("Could not resize widget: ${it.message.orEmpty()}")
            }
        }
    }

    /** Save all widget layout fields together against the latest persisted revision. */
    suspend fun saveWidgetLayout(id: Int, heightDp: Int, span: Int, paddingDp: Int): Result<Unit> {
        if (span !in 1..2 || paddingDp !in 0..OrganizationLimits.MAX_WIDGET_PADDING_DP ||
            heightDp !in OrganizationLimits.MIN_WIDGET_HEIGHT_DP..OrganizationLimits.MAX_WIDGET_HEIGHT_DP)
            return Result.failure(IllegalArgumentException("Widget layout is outside the supported range"))
        var lastFailure: Throwable = IllegalStateException("Could not save widget layout")
        repeat(3) {
            val snapshot = store.currentSnapshot()
            val current = snapshot.widgets.firstOrNull { it.appWidgetId == id }
                ?: return Result.failure(IllegalArgumentException("Widget no longer exists"))
            if (current.hostToken != currentHostToken)
                return Result.failure(IllegalStateException("Widget belongs to another host"))
            val limits = layoutLimits(current)
                ?: return Result.failure(IllegalStateException("Widget provider is unavailable"))
            if (heightDp < limits.minContentHeightDp ||
                heightDp > limits.maxContentHeightDp)
                return Result.failure(IllegalArgumentException("Widget content height is outside provider limits"))
            if (!limits.canResizeHeight && heightDp != current.heightDp)
                return Result.failure(IllegalArgumentException("Widget provider does not support height resizing"))
            // A half-span preference can be saved on a narrow screen. The Home
            // layout gives the provider a full row until a half cell can fit it.
            if (current.heightDp == heightDp && current.span == span && current.paddingDp == paddingDp)
                return Result.success(Unit)
            val updated = snapshot.widgets.map { widget ->
                if (widget.appWidgetId == id) widget.copy(heightDp = heightDp, span = span, paddingDp = paddingDp)
                else widget
            }
            val result = store.update(OrganizationBatch(
                listOf(OrganizationEdit.SetWidgets(updated)), snapshot.revision))
            if (result.isSuccess) {
                placements = result.getOrThrow().widgets
                return Result.success(Unit)
            }
            lastFailure = result.exceptionOrNull() ?: lastFailure
        }
        return Result.failure(lastFailure)
    }

    suspend fun saveWidgetGrid(id: Int, columnSpan: Int, rowSpan: Int, column: Int, row: Int): Result<Unit> {
        val widget = store.currentSnapshot().widgets.firstOrNull { it.appWidgetId == id }
            ?: return Result.failure(IllegalArgumentException("Widget no longer exists"))
        return saveWidgetGridLayout(id, columnSpan, rowSpan, column, row, widget.paddingDp)
    }

    /** Commit one valid, non-overlapping grid placement together with its padding. */
    suspend fun saveWidgetGridLayout(
        id: Int, columnSpan: Int, rowSpan: Int, column: Int, row: Int, paddingDp: Int,
    ): Result<Unit> {
        var failure: Throwable = IllegalStateException("Could not save widget layout")
        repeat(3) {
            val snapshot = store.currentSnapshot()
            val widget = snapshot.widgets.firstOrNull { it.appWidgetId == id }
                ?: return Result.failure(IllegalArgumentException("Widget no longer exists"))
            if (widget.hostToken != currentHostToken)
                return Result.failure(IllegalStateException("Widget belongs to another host"))
            val limits = layoutLimits(widget)
                ?: return Result.failure(IllegalStateException("Widget provider is unavailable"))
            val preferences = snapshot.workspacePreferences
            val geometry = calculateWidgetAreaGeometry(context.resources.configuration.screenWidthDp, preferences)
            val pageWidgets = snapshot.widgets.filter { it.pageId == widget.pageId }
            val current = packWidgetGrid(pageWidgets, preferences, geometry) { item ->
                layoutLimits(item)?.let { it.minContentWidthDp to it.minContentHeightDp } ?: (0 to 0)
            }
            val oldCell = current.cells.first { it.widget.appWidgetId == id }
            val keepLegacyHeight = widget.rowSpan == 0 && rowSpan == oldCell.rowSpan &&
                rowSpan > OrganizationLimits.MAX_WIDGET_GRID_ROW_SPAN
            if (columnSpan !in 1..preferences.gridColumnCount || rowSpan < 1 ||
                (rowSpan > OrganizationLimits.MAX_WIDGET_GRID_ROW_SPAN && !keepLegacyHeight) ||
                paddingDp !in 0..OrganizationLimits.MAX_WIDGET_PADDING_DP)
                return Result.failure(IllegalArgumentException("Widget size is outside the supported grid"))
            if ((!limits.canResizeWidth && columnSpan != oldCell.columnSpan) ||
                (!limits.canResizeHeight && rowSpan != oldCell.rowSpan))
                return Result.failure(IllegalArgumentException("This widget does not support resizing along that edge"))
            val targetColumn = if (column < 0) oldCell.column else column
            val targetRow = if (row < 0) oldCell.row else row
            if (targetColumn < 0 || targetColumn + columnSpan > preferences.gridColumnCount ||
                targetRow !in 0..OrganizationLimits.MAX_WIDGET_GRID_ROW)
                return Result.failure(IllegalArgumentException("Choose a position inside the grid"))
            val contentWidth = gridSpanWidthDp(columnSpan, geometry, preferences) - 2 * paddingDp
            val contentHeight = gridSpanHeightDp(rowSpan, preferences) - 2 * paddingDp
            if (contentWidth <= 0 || contentHeight < limits.minContentHeightDp ||
                (contentWidth < limits.minContentWidthDp && columnSpan < preferences.gridColumnCount) ||
                contentHeight > limits.maxContentHeightDp || contentWidth > limits.maxContentWidthDp)
                return Result.failure(IllegalArgumentException("Choose a size within this widget's supported dimensions"))
            val overlap = current.cells.any { other ->
                other.widget.appWidgetId != id && targetColumn < other.column + other.columnSpan &&
                    targetColumn + columnSpan > other.column && targetRow < other.row + other.rowSpan &&
                    targetRow + rowSpan > other.row
            }
            if (overlap) return Result.failure(IllegalArgumentException("That space is occupied. Move the widget or choose a smaller size."))
            val saved = widget.copy(columnSpan = columnSpan, rowSpan = if (keepLegacyHeight) 0 else rowSpan,
                gridColumn = targetColumn, gridRow = targetRow, paddingDp = paddingDp,
                heightDp = if (keepLegacyHeight) widget.heightDp else contentHeight.coerceIn(
                    OrganizationLimits.MIN_WIDGET_HEIGHT_DP, OrganizationLimits.MAX_WIDGET_HEIGHT_DP))
            val cells = current.cells.associateBy { it.widget.appWidgetId }
            val updated = snapshot.widgets.map { item ->
                if (item.appWidgetId == id) saved else cells[item.appWidgetId]?.let { cell ->
                    if (cell.row <= OrganizationLimits.MAX_WIDGET_GRID_ROW)
                        item.copy(gridColumn = cell.column, gridRow = cell.row)
                    else item.copy(gridColumn = -1, gridRow = -1)
                } ?: item
            }
            val result = store.update(OrganizationBatch(listOf(OrganizationEdit.SetWidgets(updated)), snapshot.revision))
            if (result.isSuccess) {
                placements = result.getOrThrow().widgets
                return Result.success(Unit)
            }
            failure = result.exceptionOrNull() ?: failure
        }
        return Result.failure(failure)
    }

    private suspend fun edit(transform: (List<WorkspaceWidget>) -> List<WorkspaceWidget>): Boolean {
        repeat(3) {
            val snapshot = store.currentSnapshot()
            val result = store.update(OrganizationBatch(
                listOf(OrganizationEdit.SetWidgets(transform(snapshot.widgets))), snapshot.revision))
            if (result.isSuccess) {
                placements = result.getOrThrow().widgets
                return true
            }
        }
        return false
    }

    internal fun pxToDp(px: Int): Int = ceil(px / context.resources.displayMetrics.density).toInt()
}

@Composable
fun rememberWorkspaceWidgetController(
    store: OrganizationStore,
    widgets: List<WorkspaceWidget>,
    visible: Boolean,
    onMessage: (String) -> Unit = {},
): WorkspaceWidgetController {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val controller = remember(store) { WorkspaceWidgetController(context, store, scope) }
    val bind = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        controller.bindResult(it.resultCode, it.data)
    }
    val configure = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        controller.configurationResult(it.resultCode, it.data)
    }
    SideEffect {
        controller.attach(bind::launch, configure::launch, onMessage)
        controller.updatePlacements(widgets)
        controller.setVisible(visible)
    }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.setStarted(true)
                Lifecycle.Event.ON_STOP -> controller.setStarted(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        controller.setStarted(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); controller.dispose() }
    }
    // Snapshot delivery is the gate: unreferenced host IDs cannot be deleted before persistence loads.
    androidx.compose.runtime.LaunchedEffect(controller, widgets) { controller.reconcile() }
    WidgetProviderPicker(controller)
    return controller
}
