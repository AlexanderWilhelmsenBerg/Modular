package io.github.alexanderwilhelmsenberg.modular.filesui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Menus share the embedded surface's layout and input coordinates; no child Window is created. */
private class MenuEntry(val anchor: () -> Rect, val dismiss: () -> Unit, val content: @Composable () -> Unit)
private class MenuState {
    var current by mutableStateOf<MenuEntry?>(null)
    fun dismiss(): Boolean {
        val entry = current ?: return false
        current = null
        entry.dismiss()
        return true
    }
}
private val LocalMenuState = staticCompositionLocalOf<MenuState> { error("Files menu host is missing") }

@Composable
internal fun FilesMenuHost(modifier: Modifier, onMenuStateChanged: ((() -> Boolean)?) -> Unit,
    content: @Composable () -> Unit) {
    val state = remember { MenuState() }
    val dismiss = remember(state) { { state.dismiss() } }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val entry = state.current
    val latestCallback by rememberUpdatedState(onMenuStateChanged)
    SideEffect { latestCallback(if (entry == null) null else dismiss) }
    DisposableEffect(Unit) { onDispose { latestCallback(null) } }
    CompositionLocalProvider(LocalMenuState provides state) {
        Box(modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() }) {
            Box(Modifier.fillMaxSize().then(if (entry == null) Modifier else Modifier.clearAndSetSemantics { })) {
                content()
            }
            if (entry != null) {
                Box(Modifier.fillMaxSize().testTag("files.menu.dismiss").clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClickLabel = "Close menu", onClick = { state.dismiss() }))
                Layout(modifier = Modifier.fillMaxSize(), content = { entry.content() }) { children, constraints ->
                    val margin = 8.dp.roundToPx()
                    val width = (constraints.maxWidth - margin * 2).coerceAtLeast(0)
                    val height = (constraints.maxHeight - margin * 2).coerceAtLeast(0)
                    val menu = children.single().measure(Constraints(
                        minWidth = minOf(196.dp.roundToPx(), width), maxWidth = minOf(320.dp.roundToPx(), width),
                        maxHeight = height))
                    val anchor = entry.anchor().translate(-bounds.left, -bounds.top)
                    val desiredX = if (layoutDirection == LayoutDirection.Rtl) anchor.left.roundToInt()
                        else anchor.right.roundToInt() - menu.width
                    val x = desiredX
                        .coerceIn(margin.coerceAtMost(constraints.maxWidth),
                            (constraints.maxWidth - menu.width - margin).coerceAtLeast(margin))
                    val below = anchor.bottom.roundToInt() + 4.dp.roundToPx()
                    val desiredY = if (below + menu.height <= constraints.maxHeight - margin) below
                        else anchor.top.roundToInt() - menu.height
                    val y = desiredY.coerceIn(margin.coerceAtMost(constraints.maxHeight),
                        (constraints.maxHeight - menu.height - margin).coerceAtLeast(margin))
                    layout(constraints.maxWidth, constraints.maxHeight) { menu.place(x, y) }
                }
            }
        }
    }
    BackHandler(enabled = entry != null) { state.dismiss() }
}

@Composable
internal fun FilesDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier, shape: Shape = RectangleShape, content: @Composable ColumnScope.() -> Unit) {
    val state = LocalMenuState.current
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val latestContent by rememberUpdatedState(content)
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val latestModifier by rememberUpdatedState(modifier)
    val focus = remember { FocusRequester() }
    Box(Modifier.onGloballyPositioned { anchor = it.parentLayoutCoordinates?.boundsInRoot() ?: it.boundsInRoot() })
    DisposableEffect(expanded, state) {
        val entry = if (expanded) MenuEntry({ anchor }, { latestDismiss() }) {
            Surface(modifier = latestModifier.testTag("files.menu").focusRequester(focus)
                .onPreviewKeyEvent {
                    if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) { state.dismiss(); true } else false
                }.focusable().semantics { paneTitle = "More options"; isTraversalGroup = true },
                shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) { latestContent() }
            }
            androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
        } else null
        if (entry != null) state.current = entry
        onDispose { if (state.current === entry) state.current = null }
    }
}
