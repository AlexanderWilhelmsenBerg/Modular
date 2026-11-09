package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.R

/** Home's action menu uses the same sheet, header, and row rhythm as app actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeActionsSheet(
    onDismiss: () -> Unit,
    onAddWidget: () -> Unit,
    onEdit: () -> Unit,
    onAdvanced: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("workspace-home-menu"),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                        SettingsGlyph(SettingsIcon.HOME, Modifier.size(24.dp),
                            MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Text("Home screen", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
            HomeActionRow("Add widget", "workspace-menu-add-widget", SettingsIcon.ADDONS, onAddWidget)
            HomeActionRow("Edit", "workspace-menu-edit", SettingsIcon.SORT, onEdit)
            HomeActionRow("Advanced", "workspace-menu-advanced", SettingsIcon.ADVANCED, onAdvanced)
        }
    }
}

@Composable
private fun HomeActionRow(label: String, tag: String, icon: SettingsIcon, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick)
        .testTag(tag).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon == SettingsIcon.SORT) {
            Icon(painterResource(R.drawable.ic_app_action_edit), contentDescription = null,
                modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else SettingsGlyph(icon, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}
