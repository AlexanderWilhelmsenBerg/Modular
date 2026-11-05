package io.github.alexanderwilhelmsenberg.modular.surface

import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceDocumentParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceNodeParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurfaceParcelBoundsInstrumentedTest {
    @Test
    fun normalTypedChildrenRoundTrip() {
        val document = SurfaceDocumentParcel(
            schemaVersion = 1,
            revision = 12,
            root = SurfaceNodeParcel(
                id = "root",
                type = SurfaceNodeParcel.TYPE_COLUMN,
                children = listOf(
                    SurfaceNodeParcel(
                        id = "text",
                        type = SurfaceNodeParcel.TYPE_TEXT,
                        text = "Hello",
                    ),
                    SurfaceNodeParcel(
                        id = "action",
                        type = SurfaceNodeParcel.TYPE_BUTTON,
                        text = "Ping",
                        actionId = "home.ping",
                    ),
                ),
            ),
        )

        val parcel = Parcel.obtain()
        try {
            document.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            assertEquals(
                document,
                SurfaceDocumentParcel.CREATOR.createFromParcel(parcel),
            )
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun excessiveChildCountRejectedBeforeAllocatingList() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeString("root")
            parcel.writeInt(SurfaceNodeParcel.TYPE_COLUMN)
            parcel.writeString(null)
            parcel.writeInt(SurfaceNodeParcel.TEXT_STYLE_BODY)
            parcel.writeString(null)
            parcel.writeInt(Int.MAX_VALUE)
            parcel.setDataPosition(0)

            try {
                SurfaceNodeParcel.CREATOR.createFromParcel(parcel)
                fail("Excessive child count must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    expected.message.orEmpty().contains("child count"),
                )
            }
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun excessiveNestingRejectedDuringUnmarshalling() {
        var node = SurfaceNodeParcel(
            id = "leaf",
            type = SurfaceNodeParcel.TYPE_TEXT,
            text = "Hello",
        )
        repeat(SurfaceDocumentParcel.MAX_DEPTH) { index ->
            node = SurfaceNodeParcel(
                id = "container-$index",
                type = SurfaceNodeParcel.TYPE_COLUMN,
                children = listOf(node),
            )
        }

        val parcel = Parcel.obtain()
        try {
            SurfaceDocumentParcel(1, 0, node)
                .writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            try {
                SurfaceDocumentParcel.CREATOR.createFromParcel(parcel)
                fail("Excessive nesting must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    expected.message.orEmpty().contains("depth"),
                )
            }
        } finally {
            parcel.recycle()
        }
    }
}
