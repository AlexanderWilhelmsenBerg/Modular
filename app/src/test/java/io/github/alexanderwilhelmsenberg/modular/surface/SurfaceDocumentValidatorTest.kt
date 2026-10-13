package io.github.alexanderwilhelmsenberg.modular.surface

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceDocumentParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceNodeParcel
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceDocumentValidatorTest {
    @Test
    fun validMinimalTreePasses() {
        val document = SurfaceDocumentParcel(
            schemaVersion = 1,
            revision = 0,
            root = SurfaceNodeParcel(
                id = "root",
                type = SurfaceNodeParcel.TYPE_COLUMN,
                children = listOf(
                    SurfaceNodeParcel(
                        id = "title",
                        type = SurfaceNodeParcel.TYPE_TEXT,
                        text = "Hello",
                    ),
                    SurfaceNodeParcel(
                        id = "action",
                        type = SurfaceNodeParcel.TYPE_BUTTON,
                        text = "Run",
                        actionId = "run.action",
                    ),
                ),
            ),
        )

        assertTrue(
            SurfaceDocumentValidator
                .validate(document)
                .isSuccess,
        )
    }

    @Test
    fun duplicateIdsFailClosed() {
        val document = SurfaceDocumentParcel(
            schemaVersion = 1,
            revision = 0,
            root = SurfaceNodeParcel(
                id = "root",
                type = SurfaceNodeParcel.TYPE_COLUMN,
                children = listOf(
                    SurfaceNodeParcel(
                        id = "same",
                        type = SurfaceNodeParcel.TYPE_TEXT,
                        text = "One",
                    ),
                    SurfaceNodeParcel(
                        id = "same",
                        type = SurfaceNodeParcel.TYPE_TEXT,
                        text = "Two",
                    ),
                ),
            ),
        )

        assertTrue(
            SurfaceDocumentValidator
                .validate(document)
                .isFailure,
        )
    }

    @Test
    fun excessiveDepthFailsClosed() {
        var node = SurfaceNodeParcel(
            id = "leaf",
            type = SurfaceNodeParcel.TYPE_TEXT,
            text = "leaf",
        )

        repeat(SurfaceDocumentParcel.MAX_DEPTH) { index ->
            node = SurfaceNodeParcel(
                id = "level-$index",
                type = SurfaceNodeParcel.TYPE_COLUMN,
                children = listOf(node),
            )
        }

        val document = SurfaceDocumentParcel(
            schemaVersion = 1,
            revision = 0,
            root = node,
        )

        assertTrue(
            SurfaceDocumentValidator
                .validate(document)
                .isFailure,
        )
    }

    @Test
    fun unknownNodeTypeFailsClosed() {
        val document = SurfaceDocumentParcel(
            schemaVersion = 1,
            revision = 0,
            root = SurfaceNodeParcel(
                id = "root",
                type = 999,
            ),
        )

        assertTrue(
            SurfaceDocumentValidator
                .validate(document)
                .isFailure,
        )
    }
}
