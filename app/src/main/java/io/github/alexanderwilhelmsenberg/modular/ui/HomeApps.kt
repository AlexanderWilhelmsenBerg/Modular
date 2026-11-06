package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp

/** Core deliberately renders only the app name. Decoration belongs to add-ons. */
@Composable
internal fun HomeAppRow(
    app: LaunchableApp,
    enabled: Boolean,
    unavailabilityReason: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                if (!enabled) stateDescription = unavailabilityReason ?: "Unavailable"
            }
            .testTag("app-row:${app.key.stableId}")
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            app.label,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

