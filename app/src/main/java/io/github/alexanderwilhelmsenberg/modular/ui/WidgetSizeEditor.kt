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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationLimits
import io.github.alexanderwilhelmsenberg.modular.widgets.WidgetLayoutLimits
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePreferences
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget
import io.github.alexanderwilhelmsenberg.modular.workspace.WIDGET_RAIL_INSET_DP
import io.github.alexanderwilhelmsenberg.modular.workspace.calculateWidgetAreaGeometry
import kotlin.math.ceil
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
                    "workspace-widget-area-width", { width = it })
                LayoutValueSlider("Edge margin", "$margin dp", margin, 0..48,
                    "workspace-widget-margin", { margin = it })
                LayoutValueSlider("Spacing between widgets", "$gap dp", gap, 0..48,
                    "workspace-widget-gap", { gap = it })
                Text("Set the edge margin to 0 dp to place widgets against the available edges. The alphabet rail keeps its touch space.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Half-width widgets that need more space appear on full-width rows.",
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

/** Provider-aware widget dimensions; only the Save action writes to storage. */
@Composable
internal fun WidgetSizeEditor(
    widget: WorkspaceWidget,
    preferences: WorkspacePreferences,
    limits: WidgetLayoutLimits,
    canFitHalf: (WorkspaceWidget, Int) -> Boolean,
    onDismiss: () -> Unit,
    onSave: suspend (Int, Int, Int, Int) -> Result<Unit>,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var span by remember(widget.appWidgetId) { mutableStateOf(widget.span) }
    var height by remember(widget.appWidgetId) { mutableStateOf(widget.heightDp) }
    var padding by remember(widget.appWidgetId) { mutableStateOf(widget.paddingDp) }
    var saving by remember(widget.appWidgetId) { mutableStateOf(false) }
    var error by remember(widget.appWidgetId) { mutableStateOf<String?>(null) }
    val minHeight = limits.minContentHeightDp
        .coerceIn(OrganizationLimits.MIN_WIDGET_HEIGHT_DP, OrganizationLimits.MAX_WIDGET_HEIGHT_DP)
    val maxHeight = limits.maxContentHeightDp
        .coerceIn(minHeight, OrganizationLimits.MAX_WIDGET_HEIGHT_DP)
    val validHeight = height in minHeight..maxHeight
    // Match the controller's width check when deciding whether the draft can be saved.
    val metrics = LocalContext.current.resources.displayMetrics
    val screenWidth = ceil(metrics.widthPixels / metrics.density.toDouble()).toInt()
    val halfWidth = calculateWidgetAreaGeometry(screenWidth, preferences).halfCellWidthDp
    val fitsHalf = canFitHalf(widget.copy(span = 1, paddingDp = padding), halfWidth)
    val changed = span != widget.span || height != widget.heightDp || padding != widget.paddingDp

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Widget size and layout") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Width", style = MaterialTheme.typography.titleSmall)
                if (limits.maxContentWidthDp != Int.MAX_VALUE) Text(
                    "This provider limits its content width to ${limits.maxContentWidthDp} dp.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    TextButton(enabled = !saving, onClick = { span = 2 },
                        modifier = Modifier.testTag("workspace-widget-span-full")) {
                        Text(if (span == 2) "✓ Full width" else "Full width")
                    }
                    TextButton(enabled = !saving, onClick = { span = 1 },
                        modifier = Modifier.testTag("workspace-widget-span-half")) {
                        Text(if (span == 1) "✓ Half width" else "Half width")
                    }
                }
                if (span == 1 && !fitsHalf) Text(
                    "This widget needs at least ${limits.minContentWidthDp + 2 * padding} dp; the half-width cell has $halfWidth dp. It will use a full-width row until more space is available.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (span == 1 && fitsHalf) Text("Adjacent half-width widgets can share a row.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Widget height: $height dp", style = MaterialTheme.typography.titleSmall)
                if (limits.canResizeHeight && maxHeight > minHeight) Slider(
                    value = height.coerceIn(minHeight, maxHeight).toFloat(),
                    onValueChange = { height = it.roundToInt() },
                    valueRange = minHeight.toFloat()..maxHeight.toFloat(),
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("workspace-widget-height"),
                ) else Text(if (limits.canResizeHeight) "This widget's allowed height is fixed."
                    else "This widget provider does not allow height resizing.",
                    style = MaterialTheme.typography.bodySmall)
                if (limits.canResizeHeight && maxHeight > minHeight) Row {
                    TextButton(enabled = !saving && height > minHeight,
                        onClick = { height = (height - 16).coerceAtLeast(minHeight) },
                        modifier = Modifier.testTag("workspace-widget-height-shorter")) { Text("−16 dp") }
                    TextButton(enabled = !saving && height < maxHeight,
                        onClick = { height = (height + 16).coerceAtMost(maxHeight) },
                        modifier = Modifier.testTag("workspace-widget-height-taller")) { Text("+16 dp") }
                }
                Text("Provider height: $minHeight–$maxHeight dp. With inner padding, the row uses ${height + 2 * padding} dp.",
                    style = MaterialTheme.typography.bodySmall)
                LayoutValueSlider("Inner padding", "$padding dp", padding,
                    0..OrganizationLimits.MAX_WIDGET_PADDING_DP, "workspace-widget-padding",
                    { padding = it }, enabled = !saving)
                Text("Inner padding adds space around the widget. Some providers draw their own padding too.",
                    style = MaterialTheme.typography.bodySmall)
                if (!validHeight) Text("Choose a height within the provider's allowed range.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = !saving && validHeight && changed, onClick = {
                saving = true
                error = null
                scope.launch {
                    val result = runCatching { onSave(widget.appWidgetId, height, span, padding) }
                        .getOrElse { Result.failure(it) }
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
private fun LayoutValueSlider(
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
