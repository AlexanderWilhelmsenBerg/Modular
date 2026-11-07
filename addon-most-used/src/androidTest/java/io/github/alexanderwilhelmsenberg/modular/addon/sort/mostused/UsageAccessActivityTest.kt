package io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused

import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
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
}
