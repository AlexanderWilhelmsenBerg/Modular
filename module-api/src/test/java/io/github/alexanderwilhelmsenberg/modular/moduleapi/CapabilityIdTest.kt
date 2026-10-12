package io.github.alexanderwilhelmsenberg.modular.moduleapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilityIdTest {
    @Test
    fun roundTripsCanonicalCapability() {
        val capability = CapabilityId("modular.apps.catalog", 1)

        assertEquals("modular.apps.catalog/v1", capability.wireValue)
        assertEquals(capability, CapabilityId.parse(capability.wireValue))
    }

    @Test
    fun rejectsMalformedCapabilities() {
        assertNull(CapabilityId.parse("apps.catalog/v1"))
        assertNull(CapabilityId.parse("modular.apps.catalog"))
        assertNull(CapabilityId.parse("modular.apps.catalog/v0"))
        assertNull(CapabilityId.parse("modular.Apps.catalog/v1"))
        assertNull(CapabilityId.parse("modular.apps.catalog/vx"))
    }
}
