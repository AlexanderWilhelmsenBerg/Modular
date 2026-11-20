package io.github.alexanderwilhelmsenberg.modular.webui

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class PageAddressTest {
    @Test fun dashboardKeepsPathAndQuery() {
        val url = "https://ha.example.net:8123/lovelace/kitchen?view=1"
        val page = requireNotNull(PageAddress.parse(url, true))
        assertEquals(url, page.uri.toString())
        assertTrue(page.sameOrigin(URI("https://HA.example.net:8123/auth/login")))
        assertFalse(page.sameOrigin(URI("https://ha.example.net/")))
    }

    @Test fun cleartextIsLimitedToLocalHomeAssistant() {
        for (host in listOf("homeassistant.local", "192.168.1.4", "172.31.255.5", "[fd01::1]")) {
            assertNotNull(PageAddress.parse("http://$host:8123", true))
            assertNull(PageAddress.parse("http://$host:8123", false))
        }
        for (url in listOf("http://public.example", "http://172.32.0.1", "file:///etc",
            "https://user:password@server", "javascript:alert(1)", "https://example.org:70000"))
            assertNull(PageAddress.parse(url, true))
    }
}
