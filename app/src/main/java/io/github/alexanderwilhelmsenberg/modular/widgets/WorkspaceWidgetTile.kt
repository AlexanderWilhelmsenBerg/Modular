package io.github.alexanderwilhelmsenberg.modular.widgets

import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetHostView
import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspaceWidget

/** Provider content owns its touch stream; edit controls live below it, never over it. */
@Composable
fun WorkspaceWidgetTile(
    controller: WorkspaceWidgetController,
    widget: WorkspaceWidget,
    editMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val info = controller.providerInfo(widget)
    Column(modifier.fillMaxWidth().testTag("workspace-widget:${widget.appWidgetId}")
        .padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (info == null) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Widget unavailable", style = MaterialTheme.typography.titleMedium)
                    Text("Its app may have been removed or updated. Remove it, then add it again if available.")
                    TextButton(onClick = { controller.removeWidget(widget.appWidgetId) }) { Text("Remove widget") }
                }
            }
        } else {
            key(widget.appWidgetId, widget.provider) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val widthDp = maxWidth.value.toInt().coerceAtLeast(1)
                    AndroidView(
                        factory = { controller.hostView(widget) },
                        modifier = Modifier.fillMaxWidth().height(widget.heightDp.dp),
                        update = { view ->
                            if (view is AppWidgetHostView) runCatching {
                                view.updateAppWidgetSize(null, widthDp, widget.heightDp,
                                    widthDp, widget.heightDp)
                            }
                        },
                    )
                }
            }
            if (editMode) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { controller.moveWidgetBy(widget.appWidgetId, -1) }) { Text("Move up") }
                    TextButton(onClick = { controller.moveWidgetBy(widget.appWidgetId, 1) }) { Text("Move down") }
                }
                if (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { controller.resizeWidget(widget.appWidgetId, -48) }) { Text("Shorter") }
                        TextButton(onClick = { controller.resizeWidget(widget.appWidgetId, 48) }) { Text("Taller") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (info.configure != null)
                        TextButton(onClick = { controller.reconfigureWidget(widget.appWidgetId) }) { Text("Configure") }
                    TextButton(onClick = { controller.removeWidget(widget.appWidgetId) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
internal fun WidgetProviderPicker(controller: WorkspaceWidgetController) {
    if (controller.pendingRecovery) {
        AlertDialog(
            onDismissRequest = controller::dismissPendingRecovery,
            title = { Text("Widget setup in progress") },
            text = { Text("Finish the current widget setup, or cancel it before adding another widget.") },
            confirmButton = { TextButton(onClick = controller::cancelPendingWidgetSetup) { Text("Cancel setup") } },
            dismissButton = { TextButton(onClick = controller::dismissPendingRecovery) { Text("Keep setup") } },
        )
        return
    }
    controller.pickerPageId ?: return
    val context = controller.context()
    val pm = context.packageManager
    val providers = controller.providers()
    fun appLabel(info: AppWidgetProviderInfo): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
    }.getOrDefault(info.provider.packageName)
    val groups = providers.groupBy(::appLabel)
    AlertDialog(
        onDismissRequest = controller::dismissPicker,
        title = { Text("Add widget") },
        text = {
            if (providers.isEmpty()) Text("No installed widgets are available.")
            else LazyColumn(Modifier.height(420.dp).testTag("widget-provider-list")) {
                groups.forEach { (app, appProviders) ->
                  item(key = "app:$app") {
                    Text(app, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                  }
                  items(appProviders, key = { it.provider.flattenToString() }) { info ->
                    val widgetLabel = runCatching { info.loadLabel(pm).orEmpty() }
                        .getOrDefault("").ifBlank { app }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AndroidView(factory = { viewContext ->
                            ImageView(viewContext).apply {
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                val density = viewContext.resources.displayMetrics.densityDpi
                                val preview = runCatching { info.loadPreviewImage(viewContext, density) }.getOrNull()
                                setImageDrawable(preview ?: runCatching {
                                    info.loadIcon(viewContext, density)
                                }.getOrNull())
                                contentDescription = "$widgetLabel preview"
                            }
                        }, modifier = Modifier.width(64.dp).height(64.dp))
                        Column(Modifier.weight(1f)) {
                            Text(widgetLabel, style = MaterialTheme.typography.titleSmall)
                            Text("At least ${controller.pxToDp(info.minWidth)} × ${controller.pxToDp(info.minHeight)} dp",
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { controller.chooseProvider(info) },
                                modifier = Modifier.testTag("widget-provider:${info.provider.flattenToString()}")) {
                                Text("Add $widgetLabel")
                            }
                        }
                    }
                  }
                }
            }
        },
        confirmButton = { TextButton(onClick = controller::dismissPicker) { Text("Cancel") } },
    )
}
