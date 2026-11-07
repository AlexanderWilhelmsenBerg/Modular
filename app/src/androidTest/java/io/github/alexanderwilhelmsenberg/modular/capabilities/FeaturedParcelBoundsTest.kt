package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedPinOrderRequestParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeaturedParcelBoundsTest {
    @Test fun requestRoundTripPreservesExactCatalogIdentity() {
        val request = FeaturedAppsRequestParcel(
            "roundtrip", 42, "nb-NO",
            listOf(AppSortItemParcel(AppKeyParcel("same.label", "First", 1), "App"),
                AppSortItemParcel(AppKeyParcel("same.label", "First", 2), "App")),
        )
        val copy = inParcel { request.writeToParcel(this, 0); setDataPosition(0)
            FeaturedAppsRequestParcel.CREATOR.createFromParcel(this) }
        assertEquals(request, copy)
    }

    @Test fun requestRejectsNegativeAndOversizedListCountBeforeItemsAreRead() {
        for (count in listOf(-1, FeaturedAppsRequestParcel.MAX_ITEMS + 1)) {
            rejectsListCount {
                inParcel {
                    writeString("request")
                    writeLong(1)
                    writeString("en")
                    writeInt(count)
                    setDataPosition(0)
                    FeaturedAppsRequestParcel.CREATOR.createFromParcel(this)
                }
            }
        }
    }

    @Test fun resultAndOrderRejectOversizedListCountBeforeKeysAreRead() {
        rejectsListCount {
            inParcel {
                writeString("request")
                writeLong(1)
                writeLong(1)
                writeInt(FeaturedAppsResultParcel.STATUS_SUCCESS)
                writeInt(FeaturedAppsResultParcel.STATE_READY)
                writeInt(FeaturedAppsResultParcel.MAX_PINS + 1)
                setDataPosition(0)
                FeaturedAppsResultParcel.CREATOR.createFromParcel(this)
            }
        }
        rejectsListCount {
            inParcel {
                writeString("order")
                writeLong(1)
                writeInt(FeaturedPinOrderRequestParcel.MAX_PINS + 1)
                setDataPosition(0)
                FeaturedPinOrderRequestParcel.CREATOR.createFromParcel(this)
            }
        }
    }

    private inline fun <T> inParcel(block: Parcel.() -> T): T {
        val parcel = Parcel.obtain()
        return try { parcel.block() } finally { parcel.recycle() }
    }

    private inline fun rejectsListCount(block: () -> Unit) {
        try {
            block()
            fail("Expected malformed featured list count to be rejected")
        } catch (_: IllegalArgumentException) {
            // The creator rejects the count before reading any typed elements.
        }
    }
}
