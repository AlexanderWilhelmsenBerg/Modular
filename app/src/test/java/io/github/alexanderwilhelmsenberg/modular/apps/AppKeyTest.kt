package io.github.alexanderwilhelmsenberg.modular.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppKeyTest {
    @Test
    fun stableIdIncludesProfileAndExactActivity() {
        val key = AppKey(
            packageName = "example.package",
            activityName = "example.package.MainActivity",
            profileSerial = 42,
        )

        assertEquals(
            "42:example.package/example.package.MainActivity",
            key.stableId,
        )
    }
}
