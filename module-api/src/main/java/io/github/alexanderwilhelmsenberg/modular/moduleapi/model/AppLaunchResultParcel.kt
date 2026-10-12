package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

data class AppLaunchResultParcel(
    val status: Int,
    val message: String?,
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

    companion object CREATOR : Parcelable.Creator<AppLaunchResultParcel> {
        const val STATUS_SUCCESS = 0
        const val STATUS_NOT_FOUND = 1
        const val STATUS_DENIED = 2
        const val STATUS_FAILED = 3

        override fun createFromParcel(parcel: Parcel): AppLaunchResultParcel =
            AppLaunchResultParcel(parcel)

        override fun newArray(size: Int): Array<AppLaunchResultParcel?> =
            arrayOfNulls(size)
    }
}
