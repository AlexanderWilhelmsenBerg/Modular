package io.github.alexanderwilhelmsenberg.modular.capabilities

import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSortResultValidatorTest {
    private val apps = listOf(
        app("a.pkg", "Alpha"),
        app("b.pkg", "Beta"),
        app("c.pkg", "Charlie"),
    )

    @Test
    fun acceptsExactPermutation() {
        val result = AppSortResultValidator.apply(
            input = apps,
            orderedKeys = listOf(
                key("c.pkg"),
                key("a.pkg"),
                key("b.pkg"),
            ),
        )

        assertEquals(
            listOf("Charlie", "Alpha", "Beta"),
            result.getOrThrow().map { it.label },
        )
    }

    @Test
    fun rejectsDuplicateProviderKeys() {
        val result = AppSortResultValidator.apply(
            input = apps,
            orderedKeys = listOf(
                key("a.pkg"),
                key("a.pkg"),
                key("c.pkg"),
            ),
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsUnknownProviderKeys() {
        val result = AppSortResultValidator.apply(
            input = apps,
            orderedKeys = listOf(
                key("a.pkg"),
                key("b.pkg"),
                key("unknown.pkg"),
            ),
        )

        assertTrue(result.isFailure)
    }

    private fun app(
        packageName: String,
        label: String,
    ): LaunchableApp =
        LaunchableApp(
            key = AppKey(
                packageName = packageName,
                activityName = packageName + ".MainActivity",
                profileSerial = 1,
            ),
            label = label,
            firstInstallTime = 0,
            enabled = true,
            loadingProgress = null,
        )

    private fun key(packageName: String): AppKeyParcel =
        AppKeyParcel(
            packageName = packageName,
            activityName = packageName + ".MainActivity",
            profileSerial = 1,
        )
}
