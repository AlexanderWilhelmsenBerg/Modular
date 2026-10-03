package io.github.alexanderwilhelmsenberg.modular.moduleapi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleContractTest {
    @Test
    fun currentProtocolIsSupported() {
        assertTrue(ModuleContract.isProtocolSupported(ModuleContract.CURRENT_PROTOCOL_VERSION))
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
