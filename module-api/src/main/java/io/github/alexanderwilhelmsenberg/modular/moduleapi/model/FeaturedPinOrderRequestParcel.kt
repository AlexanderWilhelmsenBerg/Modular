package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

/** A unique permutation of all saved pins, including unavailable keys. */
data class FeaturedPinOrderRequestParcel(
    val requestId: String,
    val expectedFeatureRevision: Long,
    val orderedKeys: List<AppKeyParcel>,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        requestId = requireNotNull(parcel.readString()),
        expectedFeatureRevision = parcel.readLong(),
        orderedKeys = parcel.readBoundedTypedList(AppKeyParcel.CREATOR, MAX_PINS),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(requestId)
        parcel.writeLong(expectedFeatureRevision)
        parcel.writeBoundedTypedList(orderedKeys, MAX_PINS)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<FeaturedPinOrderRequestParcel> {
        const val MAX_PINS = FeaturedParcelBounds.MAX_PINS
        override fun createFromParcel(parcel: Parcel) = FeaturedPinOrderRequestParcel(parcel)
        override fun newArray(size: Int): Array<FeaturedPinOrderRequestParcel?> = arrayOfNulls(size)
    }
}
