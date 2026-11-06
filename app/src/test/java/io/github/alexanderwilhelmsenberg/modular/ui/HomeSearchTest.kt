package io.github.alexanderwilhelmsenberg.modular.ui

import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSearchTest {
    @Test
    fun baselineOrderUsesLocaleAndStableIdentityForEqualLabels() {
        val later = app("z.pkg", "Alpha")
        val earlier = app("a.pkg", "Alpha")
        val beta = app("b.pkg", "Beta")

        assertEquals(
            listOf(earlier, later, beta),
            homeOrderedApps(listOf(beta, later, earlier), null, Locale.ENGLISH),
        )
    }

    @Test
    fun filteringPreservesSuppliedOrderAndCanBeCleared() {
        val first = app("z.pkg", "Calculator")
        val second = app("a.pkg", "Calendar")
        val other = app("b.pkg", "Browser")
        val supplied = listOf(first, other, second)
        val ordered = homeOrderedApps(listOf(second, first, other), supplied, Locale.ENGLISH)

        assertEquals(supplied, ordered)
        assertEquals(listOf(first, second), filterHomeApps(ordered, "cal", Locale.ENGLISH))
        assertEquals(supplied, filterHomeApps(ordered, "", Locale.ENGLISH))
    }

    @Test
    fun staleOrderFallsBackAndCurrentMetadataIsUsed() {
        val alpha = app("a.pkg", "Alpha")
        val beta = app("b.pkg", "Beta")
        val stale = app("old.pkg", "Old")
        val currentBeta = beta.copy(label = "Beta renamed", enabled = false)

        assertEquals(
            listOf(alpha, beta),
            homeOrderedApps(listOf(beta, alpha), listOf(stale, alpha), Locale.ENGLISH),
        )
        assertEquals(
            listOf(alpha, currentBeta),
            homeOrderedApps(listOf(currentBeta, alpha), listOf(alpha, beta), Locale.ENGLISH),
        )
        assertEquals(
            listOf(alpha, currentBeta),
            homeOrderedApps(listOf(currentBeta, alpha), listOf(alpha, alpha), Locale.ENGLISH),
        )
    }

    @Test
    fun turkishCaseMatchingUsesDeviceLocale() {
        val dotless = app("dotless.pkg", "Işık")
        val dotted = app("dotted.pkg", "İzmir")

        assertEquals(
            listOf(dotless),
            filterHomeApps(listOf(dotless, dotted), "ış", Locale.forLanguageTag("tr-TR")),
        )
        assertEquals(
            listOf(dotted),
            filterHomeApps(listOf(dotless, dotted), "iz", Locale.forLanguageTag("tr-TR")),
        )
    }

    private fun app(packageName: String, label: String) = LaunchableApp(
        key = AppKey(packageName, "$packageName.MainActivity", 0L),
        label = label,
        firstInstallTime = 1L,
        enabled = true,
        loadingProgress = null,
    )
}
