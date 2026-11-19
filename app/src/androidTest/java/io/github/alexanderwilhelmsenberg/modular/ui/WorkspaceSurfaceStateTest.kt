package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceSurfaceStateTest {
    @Test fun genericDefaultsAndFlags() {
        assertEquals(SurfaceState(), parseSurfaceState("""{"version":1}"""))
        assertEquals(SurfaceState(canGoBack = true, exclusiveGestures = false),
            parseSurfaceState("""{"version":1,"canGoBack":true,"exclusiveGestures":false}"""))
    }

    @Test fun rejectsInvalidState() {
        assertThrows(IllegalArgumentException::class.java) {
            parseSurfaceState("""{"version":2,"canGoBack":true}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSurfaceState("""{"version":1,"exclusiveGestures":"false"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSurfaceState("""{"version":1,"padding":"${"x".repeat(8192)}"}""")
        }
    }
}
