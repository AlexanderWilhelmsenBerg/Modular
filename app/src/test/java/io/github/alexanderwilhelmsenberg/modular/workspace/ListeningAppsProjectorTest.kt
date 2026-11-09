package io.github.alexanderwilhelmsenberg.modular.workspace

import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppCustomizations
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppProfile
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.organization.HeadsetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningAppsProjectorTest {
    private val first = app("first")
    private val second = app("second")
    private val disabled = app("disabled").copy(enabled = false)
    private val paused = app("paused", profile = 2)
    private val hidden = app("hidden")
    private val missing = AppKey("example.missing", "Missing", 1)
    private val catalog = AppCatalog(
        profiles = listOf(AppProfile(1, false), AppProfile(2, true)),
        apps = listOf(first, second, disabled, paused, hidden),
    )
    private val customizations = AppCustomizations(hidden = setOf(hidden.key.stableId))
    private val preferences = ListeningPreferences(enabled = true,
        apps = listOf(second.key, first.key, second.key, disabled.key, paused.key, hidden.key, missing))

    @Test fun selectedOrderAndExactEligibilityAreIndependentOfCategorySuppression() {
        val result = ListeningAppsProjector.project(catalog, customizations, preferences,
            setOf(HeadsetKind.WIRED))
        assertEquals(listOf(second.key, first.key), result.visibleApps.map { it.key })
        assertEquals(listOf(disabled.key, paused.key, hidden.key, missing), result.unavailableKeys)
        assertEquals(ListeningConnectionStatus.CONNECTED, result.status)
    }

    @Test fun unknownAndWrongOutputHideSectionButRetainPreviewAndChoices() {
        val unknown = ListeningAppsProjector.project(catalog, customizations, preferences, null)
        assertEquals(ListeningConnectionStatus.UNKNOWN, unknown.status)
        assertTrue(unknown.visibleApps.isEmpty())
        assertEquals(listOf(second.key, first.key), unknown.availableApps.map { it.key })

        val wrong = ListeningAppsProjector.project(catalog, customizations,
            preferences.copy(kind = HeadsetKind.USB), setOf(HeadsetKind.BLUETOOTH))
        assertEquals(ListeningConnectionStatus.DISCONNECTED, wrong.status)
        assertTrue(wrong.visibleApps.isEmpty())
        assertEquals(ListeningConnectionStatus.DISCONNECTED,
            ListeningAppsProjector.project(catalog, customizations, preferences, emptySet()).status)
    }

    @Test fun disabledPreferencesNeverRevealSection() {
        val result = ListeningAppsProjector.project(catalog, customizations,
            preferences.copy(enabled = false), setOf(HeadsetKind.WIRED))
        assertEquals(ListeningConnectionStatus.DISABLED, result.status)
        assertTrue(result.visibleApps.isEmpty())
    }

    private fun app(id: String, profile: Long = 1) = LaunchableApp(
        AppKey("example.$id", "$id.Main", profile), id, 0, true, null)
}
