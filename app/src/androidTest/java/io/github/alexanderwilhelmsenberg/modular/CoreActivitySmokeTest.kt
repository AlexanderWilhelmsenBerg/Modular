package io.github.alexanderwilhelmsenberg.modular

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the real recovery/settings composition and resource ownership. */
@RunWith(AndroidJUnit4::class)
class CoreActivitySmokeTest {
    @Test fun coreSettingsSurviveActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertFalse(it.isFinishing) }
            repeat(3) {
                scenario.recreate()
                scenario.onActivity { assertFalse(it.isFinishing) }
            }
        }
    }
}
