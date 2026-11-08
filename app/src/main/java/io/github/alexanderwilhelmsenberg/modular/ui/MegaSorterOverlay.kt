package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.organization.MegaSorterState

internal val SorterBlue = Color(0xFF62A9FF)
internal val SorterGreen = Color(0xFF62D4A0)

/** Place over the list inside a safe-inset container. Decoration consumes no pointer input. */
@Composable
fun MegaSorterOverlay(
    state: MegaSorterState,
    onExit: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        if (state.left != null) Box(
            Modifier.align(Alignment.CenterStart).width(16.dp).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(SorterGreen.copy(alpha = .20f), Color.Transparent)))
        )
        if (state.right != null) Box(
            Modifier.align(Alignment.CenterEnd).width(16.dp).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color.Transparent, SorterBlue.copy(alpha = .20f))))
        )
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp, start = 12.dp, end = 12.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
            shadowElevation = 4.dp,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onUndo, enabled = state.undoAvailable) { Text("Undo") }
                    TextButton(onClick = onExit, modifier = Modifier.semantics {
                        contentDescription = "Exit Mega Sorter"
                    }) { Text("✕ Exit") }
                }
                Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    state.left?.let { Text("← ${it.name}", color = SorterGreen) }
                    state.right?.let { Text("${it.name} →", color = SorterBlue) }
                }
                state.undoError?.let { Text(it, modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
