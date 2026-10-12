package io.github.alexanderwilhelmsenberg.modular.capabilities

import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppCatalogPageParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCatalogIpcMapperTest {
    private val catalog = AppCatalog(
        generation = 7,
        apps = (0 until 5).map { index ->
            LaunchableApp(
                key = AppKey(
                    packageName = "example.package.$index",
                    activityName = "example.Activity$index",
                    profileSerial = 10,
                ),
                label = "App $index",
                firstInstallTime = index.toLong(),
                enabled = true,
                loadingProgress = null,
            )
        },
    )

    @Test
    fun returnsDeterministicPageForGeneration() {
        val page = AppCatalogIpcMapper.page(
            catalog = catalog,
            expectedGeneration = 7,
            offset = 1,
            limit = 2,
        )

        assertEquals(AppCatalogPageParcel.STATUS_OK, page.status)
        assertEquals(2, page.apps.size)
        assertEquals("example.package.1", page.apps[0].key.packageName)
        assertEquals(3, page.nextOffset)
        assertTrue(page.hasMore)
    }

    @Test
    fun rejectsStaleGeneration() {
        val page = AppCatalogIpcMapper.page(
            catalog = catalog,
            expectedGeneration = 6,
            offset = 0,
            limit = 2,
        )

        assertEquals(
            AppCatalogPageParcel.STATUS_STALE_GENERATION,
            page.status,
        )
        assertTrue(page.apps.isEmpty())
    }

    @Test
    fun lastPageSignalsCompletion() {
        val page = AppCatalogIpcMapper.page(
            catalog = catalog,
            expectedGeneration = 7,
            offset = 4,
            limit = 2,
        )

        assertEquals(1, page.apps.size)
        assertEquals(5, page.nextOffset)
        assertFalse(page.hasMore)
    }

    @Test
    fun rejectsOversizedPage() {
        val page = AppCatalogIpcMapper.page(
            catalog = catalog,
            expectedGeneration = 7,
            offset = 0,
            limit = AppCatalogIpcMapper.MAX_PAGE_SIZE + 1,
        )

        assertEquals(
            AppCatalogPageParcel.STATUS_INVALID_ARGUMENT,
            page.status,
        )
    }
}
