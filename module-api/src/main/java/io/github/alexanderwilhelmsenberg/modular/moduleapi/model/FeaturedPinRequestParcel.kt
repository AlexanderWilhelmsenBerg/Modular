package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

/** Idempotent set-state edit; adding requires membership in the latest read catalog. */
data class FeaturedPinRequestParcel(
    val requestId: String,
    val catalogRevision: Long,
    val expectedFeatureRevision: Long,
    val key: AppKeyParcel,
    val pinned: Boolean,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        requestId = requireNotNull(parcel.readString()),
        catalogRevision = parcel.readLong(),
        expectedFeatureRevision = parcel.readLong(),
        key = requireNotNull(parcel.readTypedObject(AppKeyParcel.CREATOR)),
        pinned = parcel.readInt() != 0,
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(requestId)
        parcel.writeLong(catalogRevision)
        parcel.writeLong(expectedFeatureRevision)
        parcel.writeTypedObject(key, flags)
        parcel.writeInt(if (pinned) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<FeaturedPinRequestParcel> {
        override fun createFromParcel(parcel: Parcel) = FeaturedPinRequestParcel(parcel)
        override fun newArray(size: Int): Array<FeaturedPinRequestParcel?> = arrayOfNulls(size)
    }
}
