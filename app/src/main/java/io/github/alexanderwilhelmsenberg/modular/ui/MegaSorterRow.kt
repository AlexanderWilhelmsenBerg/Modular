package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import io.github.alexanderwilhelmsenberg.modular.organization.MegaSorterState
import io.github.alexanderwilhelmsenberg.modular.organization.SorterRowPhase
import io.github.alexanderwilhelmsenberg.modular.organization.SorterRowState
import io.github.alexanderwilhelmsenberg.modular.organization.SorterSide
import kotlin.math.abs
import kotlin.math.max

/** Wraps the existing HomeAppRow. Its content is always measured, including during the dot phase. */
@Composable
fun MegaSorterRow(
    row: SorterRowState,
    sorter: MegaSorterState,
    onAssign: (SorterSide) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var rowSize by remember(row.app.key) { mutableStateOf(IntSize.Zero) }
    var drag by remember(row.app.key) { mutableFloatStateOf(0f) }
    var dragging by remember(row.app.key) { mutableStateOf(false) }
    var menuOpen by remember(row.app.key) { mutableStateOf(false) }
    val density = LocalDensity.current
    val minimumDistance = with(density) { 72.dp.toPx() }
    val threshold = max(minimumDistance, rowSize.width * .22f)
    val active = sorter.active && row.available && row.phase !in setOf(
        SorterRowPhase.OUTGOING, SorterRowPhase.DOT, SorterRowPhase.RETURNING)
    val target = when (row.phase) {
        SorterRowPhase.OUTGOING, SorterRowPhase.DOT ->
            (if (row.activeSide == SorterSide.LEFT) -1f else 1f) * rowSize.width
        else -> 0f
    }
    val translation by animateFloatAsState(target, tween(160), label = "Sorter row translation")
    val opacity by animateFloatAsState(
        if (row.phase == SorterRowPhase.DOT) 0f else 1f,
        tween(160), label = "Sorter row opacity")
    val dotExpansion by animateFloatAsState(
        if (row.phase == SorterRowPhase.RETURNING) 1f else 0f,
        tween(200), label = "Sorter dot expansion")
    val stateLabel = buildList {
        if (row.leftMember) sorter.left?.let { add("${it.name} on left") }
        if (row.rightMember) sorter.right?.let { add("${it.name} on right") }
        row.error?.let(::add)
        if (!row.available) add("Unavailable")
    }.joinToString(", ").ifBlank { "Unassigned" }
    val leftAction = sorter.left?.let { destination ->
        CustomAccessibilityAction("Assign to ${destination.name} on left") { onAssign(SorterSide.LEFT); true }
    }
    val rightAction = sorter.right?.let { destination ->
        CustomAccessibilityAction("Assign to ${destination.name} on right") { onAssign(SorterSide.RIGHT); true }
    }

    Box(
        modifier = modifier.fillMaxWidth().onSizeChanged { rowSize = it }
            .pointerInput(row.app.key, sorter.left?.id, sorter.right?.id, active) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!active) return@awaitEachGesture
                    var accepted = false
                    val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        val side = if (over > 0f) SorterSide.RIGHT else SorterSide.LEFT
                        if (sorter.destination(side) != null) {
                            change.consume()
                            drag = over
                            dragging = true
                            accepted = true
                        }
                    }
                    if (slop == null || !accepted) {
                        drag = 0f
                        dragging = false
                        return@awaitEachGesture
                    }
                    var pointerDown = true
                    while (pointerDown) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            pointerDown = false
                        } else if (change.positionChanged()) {
                            drag += change.positionChange().x
                            change.consume()
                        }
                    }
                    val selectedSide = if (drag > 0) SorterSide.RIGHT else SorterSide.LEFT
                    if (abs(drag) >= threshold && sorter.destination(selectedSide) != null) {
                        onAssign(selectedSide)
                    }
                    drag = 0f
                    dragging = false
                }
            }
            .semantics {
                stateDescription = stateLabel
                customActions = listOfNotNull(leftAction, rightAction)
            }
            .testTag("sorter-row:${row.app.key.stableId}"),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val leftColor = SorterGreen.copy(alpha = .11f)
            val rightColor = SorterBlue.copy(alpha = .11f)
            if (row.leftMember && row.rightMember) {
                drawRect(leftColor, size = androidx.compose.ui.geometry.Size(size.width / 2f, size.height))
                drawRect(rightColor, topLeft = Offset(size.width / 2f, 0f),
                    size = androidx.compose.ui.geometry.Size(size.width / 2f, size.height))
            } else if (row.leftMember) drawRect(leftColor)
            else if (row.rightMember) drawRect(rightColor)
            if (row.leftMember) drawRect(SorterGreen.copy(alpha = .75f),
                size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
            if (row.rightMember) drawRect(SorterBlue.copy(alpha = .75f),
                topLeft = Offset(size.width - 3.dp.toPx(), 0f),
                size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
            if (row.phase == SorterRowPhase.DOT || row.phase == SorterRowPhase.RETURNING) {
                val color = if (row.activeSide == SorterSide.LEFT) SorterGreen else SorterBlue
                drawCircle(color.copy(alpha = .24f),
                    radius = 4.dp.toPx() + dotExpansion * size.width * .65f,
                    center = center)
            }
        }
        Box(Modifier.fillMaxWidth().graphicsLayer {
            translationX = if (dragging) drag else translation
            alpha = if (dragging) 1f else opacity
            clip = true
        }) { content() }
        if (row.available && active) {
            Box(Modifier.align(Alignment.CenterEnd)) {
                TextButton(onClick = { menuOpen = true }) { Text("⋯") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    sorter.left?.let {
                        DropdownMenuItem(text = { Text("← Assign to ${it.name}") }, onClick = {
                            menuOpen = false; onAssign(SorterSide.LEFT)
                        })
                    }
                    sorter.right?.let {
                        DropdownMenuItem(text = { Text("Assign to ${it.name} →") }, onClick = {
                            menuOpen = false; onAssign(SorterSide.RIGHT)
                        })
                    }
                }
            }
        }
        row.error?.let { Text(it, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp),
            color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
    }
}
