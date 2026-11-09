package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationLimits
import io.github.alexanderwilhelmsenberg.modular.widgets.WidgetLayoutLimits
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePreferences
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import io.github.alexanderwilhelmsenberg.modular.workspace.WIDGET_RAIL_INSET_DP
import io.github.alexanderwilhelmsenberg.modular.workspace.calculateWidgetAreaGeometry
import io.github.alexanderwilhelmsenberg.modular.workspace.gridSpanHeightDp
import io.github.alexanderwilhelmsenberg.modular.workspace.gridSpanWidthDp
import io.github.alexanderwilhelmsenberg.modular.workspace.resolveWidgetGridSpan
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Edits one set of area preferences and writes them together when Save is tapped. */
@Composable
internal fun WidgetAreaEditor(
    preferences: WorkspacePreferences,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (WorkspacePreferences, () -> Unit) -> Unit,
) {
    var width by remember { mutableStateOf(preferences.widgetAreaWidthPercent) }
    var margin by remember { mutableStateOf(preferences.widgetMarginDp) }
    var gap by remember { mutableStateOf(preferences.widgetGapDp) }
    val draft = preferences.copy(widgetAreaWidthPercent = width, widgetMarginDp = margin, widgetGapDp = gap)
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val geometry = calculateWidgetAreaGeometry(screenWidth, draft)
    val changed = draft != preferences
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Widget area") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Available width after the alphabet rail: ${(screenWidth - WIDGET_RAIL_INSET_DP).coerceAtLeast(0)} dp. Current widget area: ${geometry.widthDp} dp.",
                    style = MaterialTheme.typography.bodySmall)
                LayoutValueSlider("Area width", "$width%", width, 50..100,
                    "workspace-widget-area-width", { width = it }, enabled = !busy)
                LayoutValueSlider("Edge margin", "$margin dp", margin, 0..48,
                    "workspace-widget-margin", { margin = it }, enabled = !busy)
                LayoutValueSlider("Spacing between widgets", "$gap dp", gap, 0..48,
                    "workspace-widget-gap", { gap = it }, enabled = !busy)
                Text("Set the edge margin to 0 dp to place widgets against the available edges. The alphabet rail keeps its touch space.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Widgets that need more space use wider grid spans when possible.",
                    style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && changed, onClick = { onSave(draft, onDismiss) },
                modifier = Modifier.testTag("workspace-widget-area-save")) { Text("Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Provider-aware grid dimensions; only Save writes the size and padding together. */
@Composable
internal fun WidgetSizeEditor(
    widget: WorkspaceWidget,
    preferences: WorkspacePreferences,
    limits: WidgetLayoutLimits,
    onDismiss: () -> Unit,
    onSave: suspend (Int, Int, Int, Int, Int, Int) -> Result<Unit>,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val geometry = calculateWidgetAreaGeometry(screenWidth, preferences)
    val initial = resolveWidgetGridSpan(widget, preferences, geometry,
        limits.minContentWidthDp, limits.minContentHeightDp)
    val gridColumns = preferences.gridColumnCount.coerceIn(1, 12)
    var columnSpan by remember(widget.appWidgetId) { mutableStateOf(initial.columnSpan) }
    var rowSpan by remember(widget.appWidgetId) { mutableStateOf(initial.rowSpan) }
    var padding by remember(widget.appWidgetId) { mutableStateOf(widget.paddingDp) }
    var saving by remember(widget.appWidgetId) { mutableStateOf(false) }
    var error by remember(widget.appWidgetId) { mutableStateOf<String?>(null) }
    val width = gridSpanWidthDp(columnSpan, geometry, preferences)
    val height = gridSpanHeightDp(rowSpan, preferences)
    val contentWidth = width - 2 * padding
    val contentHeight = height - 2 * padding
    val validWidth = contentWidth > 0 &&
        (contentWidth >= limits.minContentWidthDp || columnSpan == gridColumns) &&
        contentWidth <= limits.maxContentWidthDp
    val validHeight = contentHeight >= limits.minContentHeightDp &&
        contentHeight <= limits.maxContentHeightDp
    val validRowSpan = rowSpan <= OrganizationLimits.MAX_WIDGET_GRID_ROW_SPAN ||
        (widget.rowSpan == 0 && rowSpan == initial.rowSpan)
    val changed = columnSpan != initial.columnSpan || rowSpan != initial.rowSpan ||
        padding != widget.paddingDp

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Widget size and layout") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The Home grid has $gridColumns columns and ${preferences.gridRowHeightDp} dp rows. A 4 × 4 widget spans four columns and four rows.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Size: $columnSpan × $rowSpan cells", style = MaterialTheme.typography.titleSmall)
                if (limits.canResizeWidth && gridColumns > 1) {
                    LayoutValueSlider("Columns", "$columnSpan of $gridColumns", columnSpan,
                        1..gridColumns, "workspace-widget-columns", { columnSpan = it }, enabled = !saving)
                } else Text(if (gridColumns == 1) "This grid has one column."
                    else "This widget provider does not allow width resizing. It uses $columnSpan columns.",
                    style = MaterialTheme.typography.bodySmall)
                if (limits.canResizeHeight) {
                    LayoutValueSlider("Rows", "$rowSpan", rowSpan,
                        1..maxOf(initial.rowSpan, OrganizationLimits.MAX_WIDGET_GRID_ROW_SPAN),
                        "workspace-widget-rows", { rowSpan = it }, enabled = !saving)
                } else Text("This widget provider does not allow height resizing. It uses $rowSpan rows.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Allocated size: $width × $height dp. Content after padding: $contentWidth × $contentHeight dp.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Provider content limits: at least ${limits.minContentWidthDp} × ${limits.minContentHeightDp} dp" +
                    (if (limits.maxContentWidthDp < Int.MAX_VALUE) ", width at most ${limits.maxContentWidthDp} dp" else "") +
                    (if (limits.maxContentHeightDp < Int.MAX_VALUE) ", height at most ${limits.maxContentHeightDp} dp" else "") + ".",
                    style = MaterialTheme.typography.bodySmall)
                LayoutValueSlider("Inner padding", "$padding dp", padding,
                    0..OrganizationLimits.MAX_WIDGET_PADDING_DP, "workspace-widget-padding",
                    { padding = it }, enabled = !saving)
                Text("Inner padding reduces the space available to the provider. Some providers draw their own padding too.",
                    style = MaterialTheme.typography.bodySmall)
                if (!validWidth) Text("Choose a column span that fits this provider's allowed width.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (!validHeight) Text("Choose a row span that fits this provider's allowed height.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (!validRowSpan) Text("New widget sizes can use at most ${OrganizationLimits.MAX_WIDGET_GRID_ROW_SPAN} rows. The original legacy height can be kept.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = !saving && validWidth && validHeight && validRowSpan && changed, onClick = {
                saving = true
                error = null
                scope.launch {
                    val result = runCatching {
                        onSave(widget.appWidgetId, columnSpan, rowSpan,
                            widget.gridColumn, widget.gridRow, padding)
                    }.getOrElse { Result.failure(it) }
                    saving = false
                    result.onSuccess { onSaved() }.onFailure {
                        error = it.message ?: "Could not save widget layout. Try again."
                    }
                }
            }, modifier = Modifier.testTag("workspace-widget-layout-save")) { Text("Save") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun LayoutValueSlider(
    label: String,
    valueLabel: String,
    value: Int,
    range: IntRange,
    tag: String,
    onChange: (Int) -> Unit,
    enabled: Boolean = true,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = value.coerceIn(range).toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(tag))
    }
}
