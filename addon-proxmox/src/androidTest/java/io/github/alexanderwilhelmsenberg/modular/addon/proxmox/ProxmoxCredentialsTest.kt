package io.github.alexanderwilhelmsenberg.modular.addon.proxmox

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxmoxCredentialsTest {
    @Test fun savedTokenIsEncryptedAndPinRemainsLockedAcrossInstances() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = ProxmoxCredentials(context)
        val previousServer = vault.server
        val origin = "https://fixture.invalid:8006"
        val id = "fixture@pam!modular"
        val secret = "sample-token-for-isolated-test"
        try {
            vault.setToken(origin, id, secret)
            val reopened = ProxmoxCredentials(context)
            assertEquals(origin, reopened.server)
            assertEquals("PVEAPIToken=$id=$secret", reopened.token(origin))
            assertNull(reopened.token("https://elsewhere.invalid:8006"))
            val preferences = context.getSharedPreferences("proxmox-connection", Context.MODE_PRIVATE)
            assertFalse(preferences.all.values.any { it.toString().contains(secret) })

            vault.setPin(origin, "246812")
            assertTrue(reopened.hasPin(origin))
            assertNotNull(reopened.verifyPin(origin, "000000"))
            assertNull(reopened.verifyPin(origin, "246812"))
            repeat(5) { assertNotNull(reopened.verifyPin(origin, "000000")) }
            assertNotNull(ProxmoxCredentials(context).verifyPin(origin, "246812"))
        } finally {
            vault.forgetToken(origin)
            vault.setPin(origin, "")
            vault.server = previousServer
        }
    }

    @Test fun nativeTokenCanOnlyReadInventoryAndControlContainers() {
        assertTrue(ProxmoxTokenRoutes.allowed("GET", "/cluster/resources"))
        assertTrue(ProxmoxTokenRoutes.allowed("POST", "/nodes/n1/lxc/100/status/reboot"))
        assertTrue(ProxmoxTokenRoutes.allowed("GET", "/nodes/n1/tasks/UPID%3An1%3A100%3Avzstart%3A100%3Auser%40pam%21modular%3A/status"))
        assertFalse(ProxmoxTokenRoutes.allowed("POST", "/nodes/n1/termproxy"))
        assertFalse(ProxmoxTokenRoutes.allowed("POST", "/access/ticket"))
        assertFalse(ProxmoxTokenRoutes.allowed("GET", "/nodes/n1/tasks/UPID%3A%2F..%2Fother/status"))
    }
}
