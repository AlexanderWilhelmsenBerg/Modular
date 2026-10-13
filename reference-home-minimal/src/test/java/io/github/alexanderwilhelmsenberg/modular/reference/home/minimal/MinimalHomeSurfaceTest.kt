package io.github.alexanderwilhelmsenberg.modular.reference.home.minimal

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceNodeParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalHomeSurfaceTest {
    @Test
    fun documentUsesStableSchemaAndAction() {
        val document =
            MinimalHomeSurface.document(
                actionCount = 2,
            )

        assertEquals(1, document.schemaVersion)
        assertEquals(2L, document.revision)
        assertEquals(
            SurfaceNodeParcel.TYPE_COLUMN,
            document.root.type,
        )
        assertTrue(
            document.root.children.any {
                it.actionId ==
                    MinimalHomeSurface.ACTION_PING
            },
        )
        assertTrue(
            document.root.children.any {
                it.text ==
                    "Module action count: 2"
            },
        )
    }
}
