package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

data class SurfaceActionResultParcel(
    val status: Int,
    val message: String? = null,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        status = parcel.readInt(),
        message = parcel.readString(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(status)
        parcel.writeString(message)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<SurfaceActionResultParcel> {
        const val STATUS_SUCCESS = 0
        const val STATUS_UNKNOWN_ACTION = 1
        const val STATUS_INVALID_INSTANCE = 2
        const val STATUS_FAILED = 3

        override fun createFromParcel(parcel: Parcel): SurfaceActionResultParcel =
            SurfaceActionResultParcel(parcel)

        override fun newArray(size: Int): Array<SurfaceActionResultParcel?> =
            arrayOfNulls(size)
    }
}
