package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/** Position of one shared All apps surface below the active workspace page. */
internal class WorkspaceVerticalState(initiallyOpen: Boolean = false) {
    var progress by mutableFloatStateOf(if (initiallyOpen) 1f else 0f)
        private set

    /** Consumes only drag remaining after the current list reaches its boundary. */
    fun drag(deltaY: Float, heightPx: Float): Float {
        if (heightPx <= 0f) return 0f
        val before = progress
        progress = (before - deltaY / heightPx).coerceIn(0f, 1f)
        return (before - progress) * heightPx
    }

    fun snap(open: Boolean) { progress = if (open) 1f else 0f }

    fun updateProgress(value: Float) { progress = value.coerceIn(0f, 1f) }

    /** A release can finish a started crossing; velocity alone cannot start one. */
    fun releaseTarget(velocityY: Float = 0f): Boolean = when {
        progress <= 0f -> false
        progress >= 1f -> true
        abs(velocityY) > 950f -> velocityY < 0f
        else -> progress >= 0.42f
    }
}
