package io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused

import android.app.AlertDialog
import android.content.IntentFilter
import android.os.PatternMatcher
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageAccessActivityTest {
    @Test
    fun showsStatusAndBothSettingsActions() {
        ActivityScenario.launch(UsageAccessActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val status = activity.findViewById<TextView>(R.id.usage_access_status)
                val usageButton = activity.findViewById<Button>(R.id.usage_access_button)
                val coreButton = activity.findViewById<Button>(R.id.core_settings_button)
                assertTrue(status.isShown)
                assertTrue(status.text.isNotBlank())
                assertTrue(usageButton.isShown)
                assertTrue(usageButton.isEnabled)
                assertTrue(coreButton.isShown)
                assertTrue(coreButton.isEnabled)
            }
        }
    }

    @Test
    fun firstDeniedOpenOffersExplicitChoiceAndNotNowPersistsAcrossRelaunch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeFalse(UsageAccess.isGranted(context))
        val usageMonitor = instrumentation.addMonitor(IntentFilter(Settings.ACTION_USAGE_ACCESS_SETTINGS), null, true)
        val prefs = context.getSharedPreferences("usage_access_onboarding", 0)
        val before = prefs.getBoolean("suggestion_prompt_seen", false)
        prefs.edit().remove("suggestion_prompt_seen").commit()
        try {
            ActivityScenario.launch(UsageAccessActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val dialog = activity.onboardingDialog
                    assertTrue(dialog?.isShowing == true)
                    assertEquals(
                        activity.getString(R.string.settings_suggestion_prompt_open),
                        dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).text.toString(),
                    )
                    assertEquals(
                        activity.getString(R.string.settings_suggestion_prompt_later),
                        dialog!!.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString(),
                    )
                    assertTrue(activity.findViewById<Button>(R.id.core_settings_button).isEnabled)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val dialog = activity.onboardingDialog
                    assertTrue("Unresolved first-open choice remains after recreation", dialog?.isShowing == true)
                    dialog!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                }
            }
            assertTrue(prefs.getBoolean("suggestion_prompt_seen", false))
            assertEquals("Not now must not open Android settings", 0, usageMonitor.hits)
            ActivityScenario.launch(UsageAccessActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertNull(activity.onboardingDialog)
                    assertTrue(activity.findViewById<Button>(R.id.usage_access_button).isEnabled)
                }
            }
        } finally {
            prefs.edit().putBoolean("suggestion_prompt_seen", before).commit()
            instrumentation.removeMonitor(usageMonitor)
        }
    }

    @Test
    fun appInfoActionTargetsThisAddOnPackage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeFalse(UsageAccess.isGranted(context))
        val prefs = context.getSharedPreferences("usage_access_onboarding", 0)
        val before = prefs.getBoolean("suggestion_prompt_seen", false)
        prefs.edit().putBoolean("suggestion_prompt_seen", true).commit()
        val filter = IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            addDataScheme("package")
            addDataSchemeSpecificPart(context.packageName, PatternMatcher.PATTERN_LITERAL)
        }
        val monitor = instrumentation.addMonitor(filter, null, true)
        try {
            ActivityScenario.launch(UsageAccessActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertNull(activity.onboardingDialog)
                    val guidance = activity.findViewById<TextView>(R.id.restricted_settings_guidance)
                    assertTrue(guidance.isShown)
                    assertTrue(guidance.text.contains("Allow restricted settings"))
                    activity.findViewById<Button>(R.id.app_info_button).performClick()
                }
            }
            assertTrue("Expected package-targeted App info launch", monitor.hits > 0)
        } finally {
            prefs.edit().putBoolean("suggestion_prompt_seen", before).commit()
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun positivePromptChoiceOpensOnlyThisPackagesUsageAccessScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeFalse(UsageAccess.isGranted(context))
        val prefs = context.getSharedPreferences("usage_access_onboarding", 0)
        val before = prefs.getBoolean("suggestion_prompt_seen", false)
        prefs.edit().remove("suggestion_prompt_seen").commit()
        val filter = IntentFilter(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addDataScheme("package")
            addDataSchemeSpecificPart(context.packageName, PatternMatcher.PATTERN_LITERAL)
        }
        val monitor = instrumentation.addMonitor(filter, null, true)
        try {
            ActivityScenario.launch(UsageAccessActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(0, monitor.hits)
                    val dialog = activity.onboardingDialog!!
                    assertTrue(dialog.isShowing)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
            }
            assertEquals("Only the explicit choice opens Usage Access", 1, monitor.hits)
            assertTrue(prefs.getBoolean("suggestion_prompt_seen", false))
        } finally {
            prefs.edit().putBoolean("suggestion_prompt_seen", before).commit()
            instrumentation.removeMonitor(monitor)
        }
    }
}
