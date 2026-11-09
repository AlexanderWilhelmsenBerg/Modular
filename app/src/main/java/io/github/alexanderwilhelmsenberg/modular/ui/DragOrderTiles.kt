package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Draft-only reorder surface. The caller decides when and how to persist the resulting list. */
@Composable
internal fun <T> DragOrderTiles(
    items: List<T>,
    identity: (T) -> String,
    label: (T) -> String,
    tagPrefix: String,
    enabled: Boolean,
    onMove: (Int, Int) -> Unit,
    afterItem: (@Composable (T) -> Unit)? = null,
) {
    val slotCenters = remember { mutableStateMapOf<String, Float>() }
    fun destination(index: Int, id: String, dragY: Float): Int {
        val draggedCenter = (slotCenters[id] ?: return index) + dragY
        var result = index
        if (dragY > 0f) {
            for (candidate in index + 1..items.lastIndex) {
                val center = slotCenters[identity(items[candidate])] ?: break
                if (draggedCenter < center) break
                result = candidate
            }
        } else if (dragY < 0f) {
            for (candidate in index - 1 downTo 0) {
                val center = slotCenters[identity(items[candidate])] ?: break
                if (draggedCenter > center) break
                result = candidate
            }
        }
        return result
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { index, item ->
            val id = identity(item)
            val title = label(item)
            key(id) {
                var dragY by remember { mutableFloatStateOf(0f) }
                var dragging by remember { mutableStateOf(false) }
                var optionsOpen by remember { mutableStateOf(false) }
                val target = destination(index, id, dragY)
                val actions = listOfNotNull(
                    if (enabled && index > 0) CustomAccessibilityAction("Move $title up") {
                        onMove(index, index - 1); true
                    } else null,
                    if (enabled && index < items.lastIndex) CustomAccessibilityAction("Move $title down") {
                        onMove(index, index + 1); true
                    } else null,
                )
                Column(Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f).onGloballyPositioned {
                    slotCenters[id] = it.boundsInRoot().center.y
                }) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                        .graphicsLayer { translationY = dragY }
                        .then(if (enabled) Modifier.pointerInput(id, index, items) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragging = true; dragY = 0f },
                                onDragEnd = {
                                    val targetIndex = destination(index, id, dragY)
                                    if (targetIndex != index) onMove(index, targetIndex)
                                    dragY = 0f; dragging = false
                                },
                                onDragCancel = { dragY = 0f; dragging = false },
                                onDrag = { change, amount -> change.consume(); dragY += amount.y },
                            )
                        } else Modifier)
                        .semantics {
                            contentDescription = "$title, position ${index + 1} of ${items.size}. Hold and drag to reorder."
                            customActions = actions
                        }
                        .testTag("$tagPrefix:$id"),
                    colors = CardDefaults.outlinedCardColors(containerColor =
                        if (dragging) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.outlinedCardElevation(
                        defaultElevation = if (dragging) 6.dp else 0.dp),
                ) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(if (dragging) "Move to position ${target + 1}"
                                else "Hold and drag to reorder",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { optionsOpen = true }, enabled = enabled,
                                modifier = Modifier.semantics {
                                    contentDescription = "Reorder options for $title"
                                }.testTag("$tagPrefix-options:$id")) {
                                val ink = MaterialTheme.colorScheme.onSurfaceVariant
                                Canvas(Modifier.size(20.dp)) {
                                    val radius = 1.5.dp.toPx()
                                    for (x in listOf(0.38f, 0.62f))
                                        for (y in listOf(0.25f, 0.50f, 0.75f))
                                            drawCircle(ink, radius, Offset(size.width * x, size.height * y))
                                }
                            }
                            DropdownMenu(expanded = optionsOpen,
                                onDismissRequest = { optionsOpen = false }) {
                                DropdownMenuItem(text = { Text("Move up") },
                                    enabled = index > 0, onClick = {
                                        optionsOpen = false; onMove(index, index - 1)
                                    })
                                DropdownMenuItem(text = { Text("Move down") },
                                    enabled = index < items.lastIndex, onClick = {
                                        optionsOpen = false; onMove(index, index + 1)
                                    })
                            }
                        }
                    }
                }
                afterItem?.invoke(item)
                }
            }
        }
    }
}
