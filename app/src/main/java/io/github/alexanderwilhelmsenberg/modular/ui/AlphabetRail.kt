package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** Fixed hit lane; the visual curve never changes finger-to-bucket mapping. */
@Composable
internal fun AlphabetRail(
    alphabet: List<String>,
    populated: Set<String>,
    onSelect: (String) -> Unit,
    wallpaperMode: Boolean,
    modifier: Modifier = Modifier,
) {
    if (alphabet.isEmpty()) return
    var fingerFraction by remember { mutableFloatStateOf(-1f) }
    var selected by remember { mutableStateOf<String?>(null) }
    var lastNavigationLabel by remember { mutableStateOf<String?>(null) }
    var chooser by remember { mutableStateOf(false) }
    val foreground = if (wallpaperMode) Color.White else androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    fun resolve(index: Int): String {
        val after = alphabet.drop(index).firstOrNull { it in populated }
        return after ?: alphabet.take(index).lastOrNull { it in populated } ?: alphabet[index]
    }
    fun chooseIndex(index: Int) {
        val destination = resolve(index.coerceIn(alphabet.indices))
        if (destination == selected) return
        selected = destination
        lastNavigationLabel = destination
        onSelect(destination)
    }
    BoxWithConstraints(modifier.width(112.dp).fillMaxHeight().testTag("alphabet-rail")) {
        val condensed = maxHeight / alphabet.size < (22f * LocalDensity.current.fontScale).dp
        val visual = if (condensed) alphabet.filterIndexed { i, label -> i == 0 || i == alphabet.lastIndex || i % 2 == 0 || label == "★" } else alphabet
        val density = LocalDensity.current
        val heightPx = with(density) { maxHeight.roundToPx() }
        Column(Modifier.fillMaxHeight().width(48.dp).align(AbsoluteAlignment.CenterRight)) {
            visual.forEachIndexed { visualIndex, label ->
                val centerY = (visualIndex + 0.5f) / visual.size
                val distance = if (fingerFraction < 0) 9f else abs(centerY - fingerFraction) * visual.size
                val target = if (fingerFraction < 0) 0f else -64f * exp(-0.36f * distance * distance)
                val shift by animateFloatAsState(targetValue = target, animationSpec = tween(180), label = "rail curve")
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        Modifier.offset(x = shift.dp).testTag("rail:$label"),
                        color = if (label in populated) foreground else foreground.copy(alpha = 0.38f),
                        fontSize = if (condensed) 12.sp else 18.sp,
                        fontWeight = if (label == selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
        if (fingerFraction >= 0f && selected != null) {
            Box(
                Modifier.offset { IntOffset(0, ((fingerFraction * heightPx) - with(density) { 24.dp.toPx() }).roundToInt().coerceIn(0, (heightPx - with(density) { 48.dp.roundToPx() }).coerceAtLeast(0))) }
                    .size(48.dp).background(Color(0xFF478FF2), CircleShape).align(AbsoluteAlignment.TopLeft)
                    .testTag("alphabet-bubble"),
                contentAlignment = Alignment.Center,
            ) { Text(selected.orEmpty(), color = Color.White, fontSize = 30.sp, maxLines = 1) }
        }
        Box(
            Modifier.width(48.dp).fillMaxHeight().align(AbsoluteAlignment.CenterRight)
                .pointerInput(alphabet, populated, condensed) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        try {
                            var moved = false
                            fun selectedIndex(y: Float) = ((y / size.height.coerceAtLeast(1)) * alphabet.size).toInt().coerceIn(alphabet.indices)
                            fingerFraction = (down.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            if (!condensed) chooseIndex(selectedIndex(down.position.y))
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                if (abs(change.position.y - down.position.y) > 8f || abs(change.position.x - down.position.x) > 8f) moved = true
                                fingerFraction = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                if (!condensed || moved) chooseIndex(selectedIndex(change.position.y))
                                change.consume()
                            }
                            if (condensed && !moved) chooser = true
                        } finally {
                            fingerFraction = -1f
                            selected = null
                        }
                    }
                }
                .semantics {
                    contentDescription = "Alphabet navigation"
                    customActions = listOf(
                        CustomAccessibilityAction("Next section") {
                            val current = lastNavigationLabel?.let { alphabet.indexOf(it) } ?: -1
                            chooseIndex((current + 1).coerceAtMost(alphabet.lastIndex)); true
                        },
                        CustomAccessibilityAction("Previous section") {
                            val current = lastNavigationLabel?.let { alphabet.indexOf(it) } ?: alphabet.lastIndex + 1
                            chooseIndex(current - 1); true
                        },
                        CustomAccessibilityAction("Choose section") { chooser = true; true },
                    )
                },
        )
    }
    if (chooser) AlertDialog(
        onDismissRequest = { chooser = false },
        title = { Text("Choose section") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                items(alphabet.size) { i ->
                    val label = alphabet[i]
                    TextButton(onClick = { chooseIndex(i); chooser = false }) { Text(label + if (label in populated) "" else " (next available)") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { chooser = false }) { Text("Close") } },
    )
}
