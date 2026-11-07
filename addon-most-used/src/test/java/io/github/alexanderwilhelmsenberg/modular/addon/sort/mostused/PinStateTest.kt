package io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinStateTest {
    private val a = AppKeyParcel("example.a", "example.a.Main", 10)
    private val b = AppKeyParcel("example.b", "example.b.Main", 10)
    private val otherProfile = AppKeyParcel("example.a", "example.a.Main", 20)

    @Test fun setStateIsIdempotentAndKeepsExactProfiles() {
        val first = PinState().set(a, true)!!
        assertEquals(first, first.set(a, true))
        val second = first.set(otherProfile, true)!!
        assertEquals(listOf(a, otherProfile), second.keys)
        assertEquals(2, second.revision)
        assertEquals(listOf(otherProfile), second.set(a, false)!!.keys)
    }

    @Test fun reorderRequiresEverySavedKeyAndPreservesManualOrder() {
        val state = PinState(2, listOf(a, b))
        assertNull(state.reorder(listOf(b)))
        assertNull(state.reorder(listOf(b, b)))
        assertEquals(state, state.reorder(listOf(a, b)))
        assertEquals(PinState(3, listOf(b, a)), state.reorder(listOf(b, a)))
    }

    @Test fun pinLimitDoesNotEvictExistingPins() {
        val keys = (0 until 50).map { AppKeyParcel("example.$it", "Main", 10) }
        val full = PinState(50, keys)
        assertNull(full.set(AppKeyParcel("extra", "Main", 10), true))
        assertEquals(keys, full.keys)
    }
}
