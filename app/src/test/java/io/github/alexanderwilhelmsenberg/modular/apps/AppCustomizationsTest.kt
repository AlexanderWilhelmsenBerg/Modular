package io.github.alexanderwilhelmsenberg.modular.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCustomizationsTest {
    private val personal = AppKey("example.reader", "example.reader.Main", 1)
    private val work = personal.copy(profileSerial = 2)
    private val shortcut = personal.copy(activityName = "example.reader.Shortcut")

    @Test fun customizationsTargetCompleteLaunchIdentity() {
        val original = listOf(app(personal), app(work), app(shortcut))
        val customizations = AppCustomizations(
            labels = mapOf(personal.stableId to "Books"),
            hidden = setOf(work.stableId),
        )

        assertEquals(listOf("Books", "Reader"), customizations.visibleApps(original).map { it.label })
        assertEquals(listOf(personal, shortcut), customizations.visibleApps(original).map { it.key })
        assertEquals("Reader", original.first().label)
        assertTrue(customizations.isHidden(work))
        assertFalse(customizations.isHidden(personal))
    }

    @Test fun normalizeLabelBoundsCodePointsAndBlankMeansReset() {
        assertNull(normalizeCustomAppLabel("  \n "))
        assertEquals("Books", normalizeCustomAppLabel("  Books  "))
        val emoji = "🙂".repeat(101)
        assertEquals("🙂".repeat(100), normalizeCustomAppLabel(emoji))
    }

    private fun app(key: AppKey) = LaunchableApp(key, "Reader", 0, true, null)
}
