package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt
import io.github.alexanderwilhelmsenberg.modular.organization.FolderDefinition

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderShortcut(
    folder: FolderDefinition,
    wallpaperMode: Boolean,
    onOpen: (FolderDefinition) -> Unit,
    onEdit: (FolderDefinition) -> Unit,
    onPress: (Boolean) -> Unit,
    onBounds: (String, IntRect) -> Unit,
) {
    val interactions = remember(folder.id) { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    LaunchedEffect(pressed) { onPress(pressed) }
    DisposableEffect(Unit) { onDispose { onPress(false) } }
    val foreground = if (wallpaperMode) Color.White else MaterialTheme.colorScheme.onSurface
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 60.dp)
        .onGloballyPositioned { coords ->
            val b = coords.boundsInWindow()
            onBounds(folder.id, IntRect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()))
        }
        .combinedClickable(interactionSource = interactions, indication = null,
            onClick = { onOpen(folder) }, onLongClick = { onEdit(folder) }, onLongClickLabel = "Folder settings")
        .testTag("folder-row:${folder.id}")) {
        val start = (maxWidth * 0.126f).coerceAtLeast(16.dp)
        val labelStart = (maxWidth * 0.282f).coerceAtLeast(start + 56.dp)
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(start))
            Canvas(Modifier.size(40.dp).padding(4.dp)) {
                drawPath(Path().apply {
                    moveTo(0f, size.height * .18f); lineTo(size.width * .4f, size.height * .18f)
                    lineTo(size.width * .52f, size.height * .32f); lineTo(size.width, size.height * .32f)
                    lineTo(size.width, size.height * .86f); lineTo(0f, size.height * .86f); close()
                }, foreground)
            }
            Spacer(Modifier.width(labelStart - start - 40.dp))
            Text(folder.name, Modifier.weight(1f).padding(end = 52.dp), color = foreground,
                fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
