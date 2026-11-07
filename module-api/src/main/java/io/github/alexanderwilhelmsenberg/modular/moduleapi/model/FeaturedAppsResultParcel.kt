package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

/** Pins may include absent catalog keys for management; suggestions may not. */
data class FeaturedAppsResultParcel(
    val requestId: String,
    val catalogRevision: Long,
    val featureRevision: Long,
    val status: Int,
    val featureState: Int,
    val pinnedKeys: List<AppKeyParcel>,
    val suggestedKeys: List<AppKeyParcel>,
    val message: String?,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        requestId = requireNotNull(parcel.readString()),
        catalogRevision = parcel.readLong(),
        featureRevision = parcel.readLong(),
        status = parcel.readInt(),
        featureState = parcel.readInt(),
        pinnedKeys = parcel.readBoundedTypedList(AppKeyParcel.CREATOR, MAX_PINS),
        suggestedKeys = parcel.readBoundedTypedList(AppKeyParcel.CREATOR, MAX_SUGGESTIONS),
        message = parcel.readString(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(requestId)
        parcel.writeLong(catalogRevision)
        parcel.writeLong(featureRevision)
        parcel.writeInt(status)
        parcel.writeInt(featureState)
        parcel.writeBoundedTypedList(pinnedKeys, MAX_PINS)
        parcel.writeBoundedTypedList(suggestedKeys, MAX_SUGGESTIONS)
        parcel.writeString(message)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<FeaturedAppsResultParcel> {
        const val MAX_PINS = FeaturedParcelBounds.MAX_PINS
        const val MAX_SUGGESTIONS = FeaturedParcelBounds.MAX_SUGGESTIONS
        const val STATUS_SUCCESS = 0
        const val STATUS_INVALID_ARGUMENT = 1
        const val STATUS_FAILED = 2
        const val STATE_READY = 0
        const val STATE_PERMISSION_REQUIRED = 1
        const val STATE_NO_HISTORY = 2
        override fun createFromParcel(parcel: Parcel) = FeaturedAppsResultParcel(parcel)
        override fun newArray(size: Int): Array<FeaturedAppsResultParcel?> = arrayOfNulls(size)
    }
}
