package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeAppRow(
    app: LaunchableApp,
    enabled: Boolean,
    unavailabilityReason: String? = null,
    showIcon: Boolean = true,
    icon: ImageBitmap? = null,
    wallpaperMode: Boolean = false,
    pinned: Boolean = false,
    onPinApp: ((LaunchableApp, Boolean) -> Unit)? = null,
    onClick: () -> Unit,
) {
    var menuOpen by remember(app.key) { mutableStateOf(false) }
    val foreground = if (wallpaperMode) Color.White else MaterialTheme.colorScheme.onSurface
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = if (onPinApp != null) ({ menuOpen = true }) else null,
            )
            .semantics { if (!enabled) stateDescription = unavailabilityReason ?: "Unavailable" }
            .testTag("app-row:${app.key.stableId}"),
        contentAlignment = AbsoluteAlignment.CenterLeft,
    ) {
        val iconStart = (maxWidth * 0.126f).coerceAtLeast(16.dp)
        val labelStart = (maxWidth * 0.282f).coerceAtLeast(iconStart + 56.dp)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(iconStart))
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (showIcon) {
                        if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
                        else Box(Modifier.size(36.dp).background(foreground.copy(alpha = 0.16f), RoundedCornerShape(9.dp)))
                    }
                }
                Spacer(Modifier.width(labelStart - iconStart - 40.dp))
                Text(
                    text = app.label,
                    modifier = Modifier.weight(1f).padding(end = 52.dp),
                    color = if (enabled) foreground else foreground.copy(alpha = 0.55f),
                    fontSize = 18.sp,
                    style = if (wallpaperMode) TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f), 4f)) else TextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (onPinApp != null) DropdownMenuItem(
                text = { Text(if (pinned) "Unpin from starred apps" else "Pin to starred apps") },
                onClick = { menuOpen = false; onPinApp(app, !pinned) },
            )
        }
    }
}
