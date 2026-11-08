package io.github.alexanderwilhelmsenberg.modular.ui

import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIdentityTest {
    @Test fun installedAppUsesAdaptiveBrandIconAndThemedLayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertTrue("App must declare its own icon", info.icon != 0)
        val icon = context.packageManager.getApplicationIcon(info)
        assertTrue("Launcher must receive an adaptive icon", icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull(icon.foreground)
        assertNotNull(icon.background)
        if (Build.VERSION.SDK_INT >= 33) assertNotNull("Themed icon must be available", icon.monochrome)
        assertNotNull(context.getDrawable(R.drawable.ic_modular_mark))
    }
}
