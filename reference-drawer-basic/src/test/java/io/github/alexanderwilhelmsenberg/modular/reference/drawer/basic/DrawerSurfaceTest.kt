package io.github.alexanderwilhelmsenberg.modular.reference.drawer.basic

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.*
import org.junit.Assert.*
import org.junit.Test

class DrawerSurfaceTest {
    @Test fun profilesAndPaginationBelongToEachInstance() {
        val apps = (0..60).map { app(it, 0) } + app(100, 10)
        val personal = DrawerSurface()
        val work = DrawerSurface()
        val first = personal.render("{\"profileSerial\":0}", apps)
        assertEquals(30, first.root.children.count { it.actionId?.startsWith("launch.") == true })
        assertEquals(1, work.render("{\"profileSerial\":10}", apps).root.children.count {
            it.actionId?.startsWith("launch.") == true
        })
        val next = first.root.children.single { it.id == "drawer.next" }.actionId!!
        assertTrue(personal.action(next) { fail("Paging cannot launch apps") })
        assertFalse(personal.action(next) { fail("Stale paging token must fail") })
        val second = personal.render("{\"profileSerial\":0}", apps)
        val firstLaunch = second.root.children.first { it.actionId?.startsWith("launch.") == true }.actionId!!
        var launched: AppKeyParcel? = null
        assertTrue(personal.action(firstLaunch) { launched = it })
        assertEquals("example.app30", launched!!.packageName)
        assertFalse(personal.action(first.root.children.first { it.actionId?.startsWith("launch.") == true }.actionId!!) { })
    }

    @Test fun boundedLabelsAndEmptyCatalogProduceValidShapes() {
        val document = DrawerSurface().render("{}", listOf(AppSortItemParcel(AppKeyParcel("p", "a", 0), "x".repeat(1000))))
        assertTrue(document.root.children.all { (it.text?.length ?: 0) <= 160 })
        assertTrue(DrawerSurface().render("{}", emptyList()).root.children.any { it.id == "drawer.empty" })
    }

    @Test fun malformedConfigurationFailsClosed() {
        for (value in listOf("[]", "{\"profileSerial\":\"not-a-number\"}", "x".repeat(8193))) {
            try { DrawerSurface().render(value, emptyList()); fail("Must reject invalid config") }
            catch (_: Exception) { }
        }
    }
    private fun app(index: Int, profile: Long) =
        AppSortItemParcel(AppKeyParcel("example.app$index", "Main", profile), "App $index")
}
