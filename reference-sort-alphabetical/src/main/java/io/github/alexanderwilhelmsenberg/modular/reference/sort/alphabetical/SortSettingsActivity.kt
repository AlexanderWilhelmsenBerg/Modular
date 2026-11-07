package io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract

/** Launcher entry point for the add-on and its Core configuration. */
class SortSettingsActivity : Activity() {
    private lateinit var feedback: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val spacing = dp(16)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(24)
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@SortSettingsActivity).apply {
                text = getString(R.string.sort_settings_title)
                textSize = 28f
            })
            addView(TextView(this@SortSettingsActivity).apply {
                text = getString(R.string.sort_settings_explanation)
                textSize = 16f
                setPadding(0, spacing, 0, spacing)
            })
            addView(Button(this@SortSettingsActivity).apply {
                id = R.id.core_settings_button
                text = getString(R.string.sort_settings_button)
                setOnClickListener { openCoreSettings() }
            })
        }
        feedback = TextView(this).apply {
            id = R.id.settings_feedback
            textSize = 16f
            setPadding(0, spacing, 0, 0)
        }
        content.addView(feedback)
        setContentView(ScrollView(this).apply {
            clipToPadding = false
            fillViewport = true
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

    private fun openCoreSettings() {
        feedback.text = ""
        val corePackage = "io.github.alexanderwilhelmsenberg.modular" +
            if (packageName.endsWith(".debug")) ".debug" else ""
        val intent = Intent(ModuleContract.ACTION_OPEN_MODULE_SETTINGS).apply {
            setPackage(corePackage)
            putExtra(
                ModuleContract.EXTRA_MODULE_COMPONENT,
                ComponentName(this@SortSettingsActivity, AlphabeticalSortService::class.java).flattenToString(),
            )
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            feedback.text = getString(R.string.sort_settings_unavailable)
        } catch (_: SecurityException) {
            feedback.text = getString(R.string.sort_settings_unavailable)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
