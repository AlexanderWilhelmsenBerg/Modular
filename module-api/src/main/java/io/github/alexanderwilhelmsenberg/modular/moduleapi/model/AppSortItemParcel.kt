package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

data class AppSortItemParcel(
    val key: AppKeyParcel,
    val label: String,
) : Parcelable {
    @Suppress("DEPRECATION")
    private constructor(parcel: Parcel) : this(
        key = requireNotNull(
            parcel.readParcelable<AppKeyParcel>(
                AppKeyParcel::class.java.classLoader,
            ),
        ),
        label = requireNotNull(parcel.readString()),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(key, flags)
        parcel.writeString(label)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<AppSortItemParcel> {
        override fun createFromParcel(parcel: Parcel): AppSortItemParcel =
            AppSortItemParcel(parcel)

        override fun newArray(size: Int): Array<AppSortItemParcel?> =
            arrayOfNulls(size)
    }
}
