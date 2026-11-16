package io.github.alexanderwilhelmsenberg.modular.addon.files

import org.junit.Assert.*
import org.junit.Test

class FilesActionRegistryTest {
    @Test fun passiveRefreshKeepsTheDisplayedPageUsable() {
        val registry = FilesActionRegistry<String>()
        registry.beginPage(); registry["displayed"] = "choose-original"; registry.endPage()
        registry.beginPage(); registry["refreshed"] = "choose-other"; registry.endPage()
        assertEquals("choose-original", registry["displayed"])
        assertEquals("choose-other", registry["refreshed"])
    }

    @Test fun oldPagesAreRetiredWithoutInvalidatingTheLatestPage() {
        val registry = FilesActionRegistry<Int>()
        repeat(100) { page ->
            registry.beginPage(); registry["page-$page"] = page; registry.endPage()
            assertEquals(page, registry["page-$page"])
        }
        assertNull(registry["page-0"])
        assertEquals(98, registry["page-98"])
    }

    @Test fun navigationAndConsumedActionsCannotBeReplayed() {
        val registry = FilesActionRegistry<String>()
        registry.beginPage(); registry["save"] = "document"; registry["delete"] = "review"; registry.endPage()
        registry.remove("save")
        assertNull(registry["save"])
        registry.clear()
        assertNull(registry["delete"])
    }
}
