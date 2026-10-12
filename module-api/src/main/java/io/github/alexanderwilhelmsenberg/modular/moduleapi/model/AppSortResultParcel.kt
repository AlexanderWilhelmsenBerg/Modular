package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

data class AppSortResultParcel(
    val status: Int,
    val orderedKeys: List<AppKeyParcel>,
    val message: String?,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        status = parcel.readInt(),
        orderedKeys = buildList {
            parcel.readTypedList(this, AppKeyParcel.CREATOR)
        },
        message = parcel.readString(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(status)
        parcel.writeTypedList(orderedKeys)
        parcel.writeString(message)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<AppSortResultParcel> {
        const val STATUS_SUCCESS = 0
        const val STATUS_INVALID_ARGUMENT = 1
        const val STATUS_FAILED = 2

        override fun createFromParcel(parcel: Parcel): AppSortResultParcel =
            AppSortResultParcel(parcel)

        override fun newArray(size: Int): Array<AppSortResultParcel?> =
            arrayOfNulls(size)
    }
}
