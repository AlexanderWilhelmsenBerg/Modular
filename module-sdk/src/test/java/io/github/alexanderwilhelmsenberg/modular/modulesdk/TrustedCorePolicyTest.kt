package io.github.alexanderwilhelmsenberg.modular.modulesdk

import org.junit.Assert.assertThrows
import org.junit.Test

class TrustedCorePolicyTest {
    @Test fun pinningRequiresExplicitPackageAndCertificateIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            TrustedCorePolicy.pinned(emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrustedCorePolicy.pinned(mapOf("" to setOf("a".repeat(64))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrustedCorePolicy.pinned(mapOf(TrustedCorePolicy.CORE_DEBUG_PACKAGE to emptySet()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrustedCorePolicy.pinned(mapOf(TrustedCorePolicy.CORE_DEBUG_PACKAGE to setOf("not-a-hash")))
        }
    }
}
