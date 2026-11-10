package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.organization.CategoryDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.HomePageId
import kotlin.math.roundToInt

internal fun workspacePageOrder(order: List<HomePageId>): List<HomePageId> =
    order.filter { it == HomePageId.Home || it is HomePageId.Widget || it is HomePageId.Module }

internal fun categoryPageOrder(order: List<HomePageId>): List<HomePageId> =
    order.filter { it == HomePageId.Home || it is HomePageId.Category }

internal enum class PageOrderSurface { WORKSPACE, CATEGORY }

/**
 * Keep the other surface's left/right sides and relative order while saving a
 * reordered subset. Replacing fixed list slots can move Home across other pages.
 */
internal fun mergeSurfacePageOrder(
    original: List<HomePageId>,
    reorderedSubset: List<HomePageId>,
    surface: PageOrderSurface,
): List<HomePageId> {
    val selectedBefore = if (surface == PageOrderSurface.WORKSPACE)
        workspacePageOrder(original) else categoryPageOrder(original)
    if (HomePageId.Home !in reorderedSubset || reorderedSubset.size != selectedBefore.size ||
        reorderedSubset.toSet() != selectedBefore.toSet()) return original
    val otherBefore = if (surface == PageOrderSurface.WORKSPACE)
        categoryPageOrder(original) else workspacePageOrder(original)
    val selectedHome = reorderedSubset.indexOf(HomePageId.Home)
    val otherHome = otherBefore.indexOf(HomePageId.Home)
    val workspaceLeft = if (surface == PageOrderSurface.WORKSPACE)
        reorderedSubset.take(selectedHome) else otherBefore.take(otherHome)
    val categoryLeft = if (surface == PageOrderSurface.CATEGORY)
        reorderedSubset.take(selectedHome) else otherBefore.take(otherHome)
    val workspaceRight = if (surface == PageOrderSurface.WORKSPACE)
        reorderedSubset.drop(selectedHome + 1) else otherBefore.drop(otherHome + 1)
    val categoryRight = if (surface == PageOrderSurface.CATEGORY)
        reorderedSubset.drop(selectedHome + 1) else otherBefore.drop(otherHome + 1)
    return workspaceLeft + categoryLeft + HomePageId.Home + workspaceRight + categoryRight
}

/** All apps stays fixed while category tiles move across it. */
internal fun movedCategoryPage(order: List<HomePageId>, from: Int, to: Int): List<HomePageId> {
    if (from !in order.indices || to !in order.indices || order[from] == HomePageId.Home || from == to) return order
    return order.toMutableList().apply { add(to, removeAt(from)) }
}

@Composable
fun CategoryPageOrder(
    order: List<HomePageId>,
    categories: List<CategoryDefinition>,
    onReorder: (List<HomePageId>) -> Unit,
    enabled: Boolean = true,
) {
    val names = remember(categories) { categories.associateBy { it.id } }
    val density = LocalDensity.current
    val tileStepPx = with(density) { 100.dp.toPx() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Page order", style = MaterialTheme.typography.titleMedium)
        Text("Physical left  ←  All apps  →  Physical right", style = MaterialTheme.typography.bodySmall)
        Text("Swipe right for a category on All apps’ left; swipe left for its right.", style = MaterialTheme.typography.bodySmall)
        // A physical page sequence must remain left-to-right even in an RTL locale.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("category-page-order"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                order.forEachIndexed { index, page ->
                    val category = (page as? HomePageId.Category)?.let { names[it.id] }
                    var dragPx by remember(page) { mutableFloatStateOf(0f) }
                    val dragModifier = if (page is HomePageId.Category && enabled) Modifier.pointerInput(order, page) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragPx = 0f },
                            onDragEnd = {
                                val target = (index + (dragPx / tileStepPx).roundToInt()).coerceIn(order.indices)
                                dragPx = 0f
                                if (target != index) onReorder(movedCategoryPage(order, index, target))
                            },
                            onDragCancel = { dragPx = 0f },
                            onDrag = { change, amount -> change.consume(); dragPx += amount.x },
                        )
                    } else Modifier
                    OutlinedCard(
                        modifier = Modifier.width(92.dp).then(dragModifier).testTag("page-tile:${if (page == HomePageId.Home) "all-apps" else (page as HomePageId.Category).id}"),
                        colors = CardDefaults.outlinedCardColors(containerColor = if (dragPx != 0f) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
                    ) {
                        Text(
                            text = if (page == HomePageId.Home) "All apps" else category?.name ?: "Removed",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 12.dp),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (category?.enabled == false) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        Text("Long-press and drag a category tile, or use Move left and Move right below.", style = MaterialTheme.typography.bodySmall)
        order.forEachIndexed { index, page ->
            val category = (page as? HomePageId.Category)?.let { names[it.id] } ?: return@forEachIndexed
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(category.name, modifier = Modifier.weight(1f).padding(top = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(enabled = enabled && index > 0, onClick = { onReorder(movedCategoryPage(order, index, index - 1)) }, modifier = Modifier.testTag("move-left:${category.id}")) { Text("Move left") }
                TextButton(enabled = enabled && index < order.lastIndex, onClick = { onReorder(movedCategoryPage(order, index, index + 1)) }, modifier = Modifier.testTag("move-right:${category.id}")) { Text("Move right") }
            }
        }
    }
}
