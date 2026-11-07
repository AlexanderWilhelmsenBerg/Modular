package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.math.max
import kotlin.math.min

/** Retained layout space keeps lowered rows aligned with their real hit targets. */
internal class HomeReachabilityState(initialOffsetPx: Float = 0f) {
    var offsetPx by mutableFloatStateOf(initialOffsetPx)
        private set
    var maxOffsetPx by mutableFloatStateOf(0f)
        private set

    fun setViewport(heightPx: Int) {
        maxOffsetPx = max(0f, heightPx * 0.48f)
        offsetPx = offsetPx.coerceIn(0f, maxOffsetPx)
    }

    fun reset() { offsetPx = 0f }

    fun nestedScroll(listState: LazyListState): NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y >= 0f || offsetPx <= 0f) return Offset.Zero
            val taken = max(available.y, -offsetPx)
            offsetPx += taken
            return Offset(0f, taken)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // A positive unconsumed delta means the list has reached its top even when
            // the zero-height leading item makes firstVisibleItemIndex nonzero.
            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            val taken = min(maxOffsetPx - offsetPx, available.y * 0.7f)
            offsetPx += taken
            return Offset(0f, taken)
        }
    }
}
