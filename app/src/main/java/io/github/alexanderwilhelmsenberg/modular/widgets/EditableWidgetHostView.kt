package io.github.alexanderwilhelmsenberg.modular.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration

/** Normal RemoteViews interactions remain native; a stationary hold selects launcher editing. */
internal class EditableWidgetHostView(context: Context) : AppWidgetHostView(context) {
    var onEditRequested: (() -> Unit)? = null
    private var downEvent: MotionEvent? = null
    private var editingGesture = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val editHold = Runnable {
        val original = downEvent ?: return@Runnable
        val action = onEditRequested ?: return@Runnable
        if (!isAttachedToWindow) return@Runnable
        editingGesture = true
        val cancel = MotionEvent.obtain(original).apply { this.action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        clearPendingHold()
        action()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearPendingHold()
                editingGesture = false
                if (onEditRequested != null) {
                    downEvent = MotionEvent.obtain(event)
                    postDelayed(editHold, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> downEvent?.let {
                if (kotlin.math.abs(event.x - it.x) > slop || kotlin.math.abs(event.y - it.y) > slop)
                    clearPendingHold()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> clearPendingHold()
        }
        if (editingGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                editingGesture = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        val handled = super.dispatchTouchEvent(event)
        // Even a static, non-clickable RemoteViews root must own its stationary
        // hold, rather than opening the empty-space menu behind the widget.
        return handled || downEvent != null
    }

    override fun cancelLongPress() {
        clearPendingHold()
        super.cancelLongPress()
    }

    override fun onDetachedFromWindow() {
        clearPendingHold()
        onEditRequested = null
        super.onDetachedFromWindow()
    }

    private fun clearPendingHold() {
        removeCallbacks(editHold)
        downEvent?.recycle()
        downEvent = null
    }
}
