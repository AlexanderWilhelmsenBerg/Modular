package io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused

import android.app.Activity
import android.app.AlertDialog
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract

/** User-facing status and explicit route to Android's special access screen. */
class UsageAccessActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var feedback: TextView
    private lateinit var restrictedGuidance: TextView
    private lateinit var appInfoButton: Button
    internal var onboardingDialog: AlertDialog? = null
        private set
    private var statusGeneration = 0
    private var promptHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = dp(24)
        val spacing = dp(16)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.settings_heading)
            textSize = 28f
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.settings_explanation)
            textSize = 16f
            setPadding(0, spacing, 0, spacing)
        })
        status = TextView(this).apply {
            id = R.id.usage_access_status
            textSize = 18f
            setPadding(0, spacing, 0, spacing)
            text = getString(R.string.settings_access_checking)
        }
        content.addView(status)
        content.addView(Button(this).apply {
            id = R.id.usage_access_button
            text = getString(R.string.settings_access_button)
            setOnClickListener { openUsageAccessSettings() }
        })
        content.addView(Button(this).apply {
            id = R.id.core_settings_button
            text = getString(R.string.settings_core_button)
            setOnClickListener { openCoreSettings() }
        })
        restrictedGuidance = TextView(this).apply {
            id = R.id.restricted_settings_guidance
            textSize = 14f
            text = getString(R.string.settings_restricted_guidance)
            setPadding(0, spacing, 0, spacing)
            visibility = View.GONE
        }
        content.addView(restrictedGuidance)
        appInfoButton = Button(this).apply {
            id = R.id.app_info_button
            text = getString(R.string.settings_app_info_button)
            visibility = View.GONE
            setOnClickListener { openAppInfo() }
        }
        content.addView(appInfoButton)
        feedback = TextView(this).apply {
            id = R.id.settings_feedback
            textSize = 16f
            setPadding(0, spacing, 0, 0)
        }
        content.addView(feedback)
        content.addView(TextView(this).apply {
            text = getString(R.string.settings_profile_note)
            textSize = 14f
            setPadding(0, spacing, 0, 0)
        })
        setContentView(ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
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
        promptHandled = savedInstanceState?.getBoolean(STATE_PROMPT_HANDLED) ?: false
        if (!promptHandled && !preferences().getBoolean(PREF_PROMPT_SEEN, false)) {
            if (UsageAccess.isGranted(this)) markPromptSeen()
            else showSuggestionPrompt()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PROMPT_HANDLED, promptHandled)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        val generation = ++statusGeneration
        if (!UsageAccess.isGranted(this)) {
            status.text = getString(R.string.settings_access_off)
            restrictedGuidance.visibility = View.VISIBLE
            appInfoButton.visibility = View.VISIBLE
            return
        }
        restrictedGuidance.visibility = View.GONE
        appInfoButton.visibility = View.GONE
        status.text = getString(R.string.settings_access_checking)
        // The platform query can block, so keep the permission button responsive.
        Thread {
            val message = try {
                val now = System.currentTimeMillis()
                val manager = getSystemService(UsageStatsManager::class.java)
                    ?: throw IllegalStateException("Usage statistics service unavailable")
                val history = manager.queryAndAggregateUsageStats(
                    now - 7L * 24L * 60L * 60L * 1_000L, now,
                ).orEmpty()
                if (history.values.any { it.totalTimeInForeground > 0L }) {
                    R.string.settings_access_on
                } else {
                    R.string.settings_access_no_history
                }
            } catch (_: RuntimeException) {
                R.string.settings_access_history_unavailable
            }
            runOnUiThread {
                if (generation == statusGeneration && !isFinishing) {
                    status.text = getString(message)
                }
            }
        }.start()
    }

    override fun onPause() {
        statusGeneration++
        super.onPause()
    }

    override fun onDestroy() {
        onboardingDialog?.dismiss()
        onboardingDialog = null
        super.onDestroy()
    }

    private fun showSuggestionPrompt() {
        onboardingDialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_suggestion_prompt_title)
            .setMessage(R.string.settings_suggestion_prompt_body)
            .setPositiveButton(R.string.settings_suggestion_prompt_open) { _, _ ->
                markPromptSeen()
                openUsageAccessSettings()
            }
            .setNegativeButton(R.string.settings_suggestion_prompt_later) { _, _ -> markPromptSeen() }
            .setOnCancelListener { markPromptSeen() }
            .create().also { it.show() }
    }

    private fun markPromptSeen() {
        promptHandled = true
        preferences().edit().putBoolean(PREF_PROMPT_SEEN, true).apply()
        onboardingDialog = null
    }

    private fun preferences() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun openAppInfo() {
        feedback.text = ""
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            feedback.text = getString(R.string.settings_app_info_unavailable)
        } catch (_: SecurityException) {
            feedback.text = getString(R.string.settings_app_info_unavailable)
        }
    }

    private fun openUsageAccessSettings() {
        feedback.text = ""
        val targeted = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(targeted)
        } catch (_: ActivityNotFoundException) {
            openGlobalUsageAccessSettings()
        } catch (_: SecurityException) {
            openGlobalUsageAccessSettings()
        }
    }

    private fun openGlobalUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            feedback.text = getString(R.string.settings_access_unavailable)
        } catch (_: SecurityException) {
            feedback.text = getString(R.string.settings_access_unavailable)
        }
    }

    private fun openCoreSettings() {
        feedback.text = ""
        val corePackage = "io.github.alexanderwilhelmsenberg.modular" +
            if (packageName.endsWith(".debug")) ".debug" else ""
        val intent = Intent(ModuleContract.ACTION_OPEN_MODULE_SETTINGS).apply {
            setPackage(corePackage)
            putExtra(
                ModuleContract.EXTRA_MODULE_COMPONENT,
                ComponentName(this@UsageAccessActivity, MostUsedSortService::class.java).flattenToString(),
            )
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            feedback.text = getString(R.string.settings_core_unavailable)
        } catch (_: SecurityException) {
            feedback.text = getString(R.string.settings_core_unavailable)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PREFS_NAME = "usage_access_onboarding"
        const val PREF_PROMPT_SEEN = "suggestion_prompt_seen"
        const val STATE_PROMPT_HANDLED = "prompt_handled"
    }
}
