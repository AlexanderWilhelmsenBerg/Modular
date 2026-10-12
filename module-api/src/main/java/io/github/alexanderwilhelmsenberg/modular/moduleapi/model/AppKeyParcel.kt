package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

data class AppKeyParcel(
    val packageName: String,
    val activityName: String,
    val profileSerial: Long,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        packageName = requireNotNull(parcel.readString()),
        activityName = requireNotNull(parcel.readString()),
        profileSerial = parcel.readLong(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(packageName)
        parcel.writeString(activityName)
        parcel.writeLong(profileSerial)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<AppKeyParcel> {
        override fun createFromParcel(parcel: Parcel): AppKeyParcel =
            AppKeyParcel(parcel)

        override fun newArray(size: Int): Array<AppKeyParcel?> =
            arrayOfNulls(size)
    }
}
