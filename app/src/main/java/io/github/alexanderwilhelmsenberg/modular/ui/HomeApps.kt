package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.UserManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun HomeAppRow(
    app: LaunchableApp,
    profileName: String,
    launcherApps: LauncherApps?,
    userManager: UserManager?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var iconBitmap by remember(app.key.stableId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(app.key.stableId, launcherApps, userManager) {
        iconBitmap = withContext(Dispatchers.IO) {
            runCatching {
                val user = userManager?.getUserForSerialNumber(app.key.profileSerial)
                val activity = if (user == null || launcherApps == null) null else {
                    launcherApps.getActivityList(app.key.packageName, user)
                        .firstOrNull { it.componentName.className == app.key.activityName }
                }
                val drawable = activity?.getBadgedIcon(context.resources.displayMetrics.densityDpi)
                if (drawable == null) null else {
                    val width = drawable.intrinsicWidth.coerceIn(1, 96)
                    val height = drawable.intrinsicHeight.coerceIn(1, 96)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        drawable.setBounds(0, 0, width, height)
                        drawable.draw(Canvas(bitmap))
                    }
                }
            }.getOrNull()
        }
    }

    val stateText = when {
        !enabled -> "$profileName, unavailable"
        app.loadingProgress != null && app.loadingProgress < 1f -> "$profileName, installing"
        else -> profileName
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = "${app.label}, $stateText"
            }
            .testTag("app-row:${app.key.stableId}")
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
            )
        } else {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(app.label.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = if (app.loadingProgress != null && app.loadingProgress < 1f) {
                "$profileName · Installing"
            } else {
                profileName
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}
