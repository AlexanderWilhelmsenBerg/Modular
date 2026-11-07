package io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** User-facing status and explicit route to Android's special access screen. */
class UsageAccessActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val spacing = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.settings_heading)
            textSize = 26f
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.settings_explanation)
            textSize = 16f
            setPadding(0, spacing, 0, spacing)
        })
        status = TextView(this).apply { textSize = 18f }
        content.addView(status)
        content.addView(Button(this).apply {
            text = getString(R.string.settings_access_button)
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    status.text = getString(R.string.settings_access_unavailable)
                }
            }
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.settings_profile_note)
            textSize = 14f
            setPadding(0, spacing, 0, 0)
        })
        setContentView(ScrollView(this).apply {
            clipToPadding = false
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val safe = insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                    )
                    view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    val left = maxOf(insets.systemWindowInsetLeft, insets.displayCutout?.safeInsetLeft ?: 0)
                    @Suppress("DEPRECATION")
                    val top = maxOf(insets.systemWindowInsetTop, insets.displayCutout?.safeInsetTop ?: 0)
                    @Suppress("DEPRECATION")
                    val right = maxOf(insets.systemWindowInsetRight, insets.displayCutout?.safeInsetRight ?: 0)
                    @Suppress("DEPRECATION")
                    val bottom = maxOf(insets.systemWindowInsetBottom, insets.displayCutout?.safeInsetBottom ?: 0)
                    view.setPadding(left, top, right, bottom)
                }
                insets
            }
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        })
    }

    override fun onResume() {
        super.onResume()
        status.text = getString(
            if (UsageAccess.isGranted(this)) R.string.settings_access_on else R.string.settings_access_off,
        )
    }
}
