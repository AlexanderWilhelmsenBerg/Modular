package io.github.alexanderwilhelmsenberg.modular.moduleapi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleContractTest {
    @Test
    fun supportedProtocolRangeIncludesDiscoverySessionsAndRequests() {
        assertTrue(ModuleContract.isProtocolSupported(1))
        assertTrue(ModuleContract.isProtocolSupported(2))
        assertTrue(ModuleContract.isProtocolSupported(3))
    }

    @Test
    fun onlyV2AndLaterSupportedProtocolsHaveSessions() {
        assertFalse(ModuleContract.supportsSessions(1))
        assertTrue(ModuleContract.supportsSessions(2))
        assertTrue(ModuleContract.supportsSessions(3))
    }

    @Test
    fun onlyV3AndLaterDeclareRequestedCoreCapabilities() {
        assertFalse(
            ModuleContract.supportsCoreCapabilityRequests(1),
        )
        assertFalse(
            ModuleContract.supportsCoreCapabilityRequests(2),
        )
        assertTrue(
            ModuleContract.supportsCoreCapabilityRequests(3),
        )
    }

    @Test
    fun unknownProtocolVersionsAreRejected() {
        assertFalse(ModuleContract.isProtocolSupported(0))
        assertFalse(
            ModuleContract.isProtocolSupported(
                ModuleContract.CURRENT_PROTOCOL_VERSION + 1,
            ),
        )
    }
}
