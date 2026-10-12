package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

data class AppProfileParcel(
    val serialNumber: Long,
    val quietModeEnabled: Boolean,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        serialNumber = parcel.readLong(),
        quietModeEnabled = parcel.readInt() != 0,
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(serialNumber)
        parcel.writeInt(if (quietModeEnabled) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<AppProfileParcel> {
        override fun createFromParcel(parcel: Parcel): AppProfileParcel =
            AppProfileParcel(parcel)

        override fun newArray(size: Int): Array<AppProfileParcel?> =
            arrayOfNulls(size)
    }
}
