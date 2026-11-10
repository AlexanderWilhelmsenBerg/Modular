package io.github.alexanderwilhelmsenberg.modular.filesui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesStorageMapTest {
    @Test fun rectanglesStayInsideMapAndTrackMeasuredBytes() {
        val weights = listOf(80L, 10L, 10L)
        val rects = storageMapRects(weights, 200f, 100f)
        assertEquals(3, rects.size)
        val totalArea = 200f * 100f
        rects.forEachIndexed { index, rect ->
            assertTrue(rect.x >= 0f && rect.y >= 0f)
            assertTrue(rect.x + rect.width <= 200.001f)
            assertTrue(rect.y + rect.height <= 100.001f)
            assertEquals(weights[index] / 100f, rect.width * rect.height / totalArea, 0.001f)
        }
    }

    @Test fun noMeasuredBytesProducesNoClaimedArea() {
        val rects = storageMapRects(listOf(0L, 0L), 200f, 100f)
        assertTrue(rects.all { it.width == 0f && it.height == 0f })
    }
}
