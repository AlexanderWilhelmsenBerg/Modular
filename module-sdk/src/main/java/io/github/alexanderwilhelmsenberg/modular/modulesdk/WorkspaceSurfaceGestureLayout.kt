package io.github.alexanderwilhelmsenberg.modular.modulesdk

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/** Optional horizontal edge navigation when the provider opts out of exclusive gestures. */
class WorkspaceSurfaceGestureLayout(
    context: Context,
    private val scope: WorkspaceSurfaceScope,
) : FrameLayout(context) {
    private var downX = 0f
    private var downY = 0f
    private var intercepted = false
    private var disallowed = false
    private val threshold = 72f * resources.displayMetrics.density

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        disallowed = disallowIntercept
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                intercepted = false; disallowed = false
            }
            MotionEvent.ACTION_MOVE -> if (!disallowed && !scope.exclusiveGestures()) {
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) >= threshold && abs(dx) > abs(dy) * 1.5f) {
                    intercepted = true
                    return true // Android sends ACTION_CANCEL to the child.
                }
            }
        }
        return intercepted
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!intercepted) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                scope.requestNavigation(if (event.x > downX) "previous" else "next")
                intercepted = false
            }
            MotionEvent.ACTION_CANCEL -> intercepted = false
        }
        return true
    }
}
