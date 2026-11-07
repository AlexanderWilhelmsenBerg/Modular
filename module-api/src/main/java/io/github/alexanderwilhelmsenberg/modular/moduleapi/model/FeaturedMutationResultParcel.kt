package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

/** Success means the edit was durably committed before the reply. */
data class FeaturedMutationResultParcel(
    val requestId: String,
    val status: Int,
    val featureRevision: Long,
    val message: String?,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        requestId = requireNotNull(parcel.readString()),
        status = parcel.readInt(),
        featureRevision = parcel.readLong(),
        message = parcel.readString(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(requestId)
        parcel.writeInt(status)
        parcel.writeLong(featureRevision)
        parcel.writeString(message)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<FeaturedMutationResultParcel> {
        const val STATUS_SUCCESS = 0
        const val STATUS_INVALID_ARGUMENT = 1
        const val STATUS_CONFLICT = 2
        const val STATUS_FAILED = 3
        override fun createFromParcel(parcel: Parcel) = FeaturedMutationResultParcel(parcel)
        override fun newArray(size: Int): Array<FeaturedMutationResultParcel?> = arrayOfNulls(size)
    }
}
