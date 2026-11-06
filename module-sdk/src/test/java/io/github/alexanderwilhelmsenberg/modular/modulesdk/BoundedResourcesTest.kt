package io.github.alexanderwilhelmsenberg.modular.modulesdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedResourcesTest {
    @Test fun capacityRejectsNewIdsButAllowsAnExistingIdToUpdate() {
        val retired = mutableListOf<Any>()
        val resources = BoundedResources<String, Any>(2, retired::add)
        val first = Any()
        val second = Any()
        val replacement = Any()
        resources.put("one", first)
        resources.put("two", second)

        assertThrows(IllegalStateException::class.java) { resources.put("three", Any()) }
        assertEquals(2, resources.size)
        assertTrue(retired.isEmpty())

        resources.put("one", replacement)
        assertSame(replacement, resources["one"])
        assertEquals(listOf(first), retired)
        assertEquals(2, resources.size)
    }

    @Test fun staleDeathCallbackCannotRetireReplacement() {
        val retired = mutableListOf<Any>()
        val resources = BoundedResources<String, Any>(1, retired::add)
        val oldSession = Any()
        val newSession = Any()
        resources.put("session", oldSession)
        resources.put("session", newSession)

        assertFalse(resources.removeIfSame("session", oldSession))
        assertSame(newSession, resources["session"])
        assertTrue(resources.removeIfSame("session", newSession))
        assertEquals(listOf(oldSession, newSession), retired)
        assertEquals(0, resources.size)
    }

    @Test fun closeRetiresEachLiveInstanceExactlyOnce() {
        val retired = mutableListOf<Any>()
        val resources = BoundedResources<String, Any>(2, retired::add)
        val one = Any()
        val two = Any()
        resources.put("one", one)
        resources.put("two", two)
        resources.remove("one")
        resources.clear()
        resources.clear()

        assertEquals(listOf(one, two), retired)
    }
}
