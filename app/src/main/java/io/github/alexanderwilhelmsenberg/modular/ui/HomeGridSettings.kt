package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import io.github.alexanderwilhelmsenberg.modular.workspace.WorkspacePreferences
import kotlinx.coroutines.launch

/** Grid dimensions are committed together; area geometry has its own focused editor. */
@Composable
fun HomeGridSettingsUi(
    snapshot: OrganizationSnapshot,
    onUpdate: suspend (OrganizationBatch) -> Result<OrganizationSnapshot>,
) {
    val scope = rememberCoroutineScope()
    val saved = snapshot.workspacePreferences
    var columns by remember { mutableStateOf(saved.gridColumnCount) }
    var rowHeight by remember { mutableStateOf(saved.gridRowHeightDp) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editArea by remember { mutableStateOf(false) }
    val draft = saved.copy(gridColumnCount = columns, gridRowHeightDp = rowHeight)

    fun save(preferences: WorkspacePreferences, onSuccess: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val result = runCatching {
                onUpdate(OrganizationBatch(
                    listOf(OrganizationEdit.SetWorkspacePreferences(preferences)),
                    expectedRevision = snapshot.revision,
                ))
            }.getOrElse { Result.failure(it) }
            busy = false
            result.onSuccess { onSuccess() }.onFailure {
                error = it.message ?: "Could not save Home grid. Try again."
            }
        }
    }

    Column(Modifier.fillMaxWidth().testTag("home-grid-settings-content"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Grid size", style = MaterialTheme.typography.titleMedium)
        Text("Widgets snap to columns and rows. A 4 × 4 widget uses four columns and four rows.",
            style = MaterialTheme.typography.bodyMedium)
        LayoutValueSlider("Columns", "$columns", columns, 1..12,
            "home-grid-columns", { columns = it }, enabled = !busy)
        LayoutValueSlider("Row height", "$rowHeight dp", rowHeight, 32..256,
            "home-grid-row-height", { rowHeight = it }, enabled = !busy)
        Text("At this row height, four rows are ${rowHeight * 4} dp before spacing and widget padding.",
            style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("home-grid-error")) }
        Button(onClick = { save(draft) }, enabled = !busy && draft != saved,
            modifier = Modifier.fillMaxWidth().testTag("home-grid-save")) { Text("Save grid") }
        Text("Widget area", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp))
        OutlinedButton(onClick = { error = null; editArea = true }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("workspace-widget-area")) {
            Text("Edit widget area")
        }
        Text("Width ${saved.widgetAreaWidthPercent}% · Edge ${saved.widgetMarginDp} dp · Spacing ${saved.widgetGapDp} dp",
            style = MaterialTheme.typography.bodySmall)
    }

    if (editArea) WidgetAreaEditor(
        preferences = saved,
        busy = busy,
        error = error,
        onDismiss = { editArea = false },
        onSave = { preferences, onSuccess -> save(preferences, onSuccess) },
    )
}
