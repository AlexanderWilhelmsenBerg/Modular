package io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical

import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SortSettingsActivityTest {
    @Test
    fun showsCoreSettingsAction() {
        ActivityScenario.launch(SortSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val coreButton = activity.findViewById<Button>(R.id.core_settings_button)
                assertTrue(coreButton.isShown)
                assertTrue(coreButton.isEnabled)
            }
        }
    }
}
