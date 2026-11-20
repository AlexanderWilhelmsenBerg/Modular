package io.github.alexanderwilhelmsenberg.modular.webui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspaceSurfaceScope
import kotlin.math.abs

/** Shared control treatment for the module-owned setup and status screens. */
class AddonScreen(val context: Context, val dark: Boolean, background: Int? = null) {
    val ink = Color.parseColor(if (dark) "#EDF3F9" else "#182635")
    val muted = Color.parseColor(if (dark) "#A9B9CA" else "#4A5F72")
    val accent = Color.parseColor(if (dark) "#A9DBFF" else "#135581")
    val surface = Color.parseColor(if (dark) "#183041" else "#D8EBF7")
    val paper = background ?: Color.parseColor(if (dark) "#101A25" else "#F3F8FC")

    fun dp(size: Int) = (size * context.resources.displayMetrics.density + .5f).toInt()

    fun column(workspace: WorkspaceSurfaceScope? = null): LinearLayout {
        val layout = if (workspace == null) LinearLayout(context) else object : LinearLayout(context) {
            private var edge = false
            private var startX = 0f
            private var startY = 0f
            private var navigating = false

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val border = minOf(width * .23f, dp(76).toFloat())
                        startX = event.x
                        startY = event.y
                        edge = startX <= border || startX >= width - border
                        navigating = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(event.x - startX)
                        val dy = abs(event.y - startY)
                        if (edge && dy > dp(16) && dy > dx) edge = false
                        if (edge && !navigating && dx >= dp(56) && dx > dy * 1.5f) {
                            // WebView keeps control of ordinary taps and scrolling. Cancel its
                            // stream only when this side gesture becomes a workspace swipe.
                            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                            navigating = true
                        }
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> edge = false
                    MotionEvent.ACTION_UP -> if (navigating) {
                        if (edge && abs(event.x - startX) >= dp(56)) {
                            workspace.requestNavigation(if (event.x > startX) "previous" else "next")
                        }
                        navigating = false
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> if (navigating) {
                        navigating = false
                        return true
                    }
                }
                return navigating || super.dispatchTouchEvent(event)
            }
        }
        return layout.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
        }
    }

    fun text(value: String, size: Float = 15f, secondary: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(if (secondary) muted else ink)
        if (size >= 20) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun section(parent: LinearLayout, title: String, description: String? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            addView(text(title, 24f))
            if (description != null) addView(text(description, secondary = true).apply {
                setPadding(0, dp(6), 0, dp(12))
            })
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    fun bar(parent: LinearLayout, title: String): LinearLayout = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(10), 0)
        minimumHeight = dp(56)
        addView(text(title, 20f), LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    fun button(label: String, primary: Boolean = false, click: (() -> Unit)? = null) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        minWidth = 0
        minimumHeight = dp(44)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(muted, if (primary) (if (dark) Color.rgb(16, 37, 52) else Color.WHITE) else accent),
        ))
        val fill = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(if (primary) accent else surface)
        }
        background = RippleDrawable(ColorStateList.valueOf((if (primary) 0x26000000 else 0x26088BCC)), fill, null)
        click?.let { listener -> setOnClickListener { listener() } }
    }

    fun install(activity: Activity, content: ViewGroup) {
        content.setOnApplyWindowInsetsListener { view, insets ->
            val safe = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            } else {
                @Suppress("DEPRECATION")
                android.graphics.Insets.of(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        activity.setContentView(content)
    }
}
