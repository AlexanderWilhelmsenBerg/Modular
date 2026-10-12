package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

data class AppSortRequestParcel(
    val items: List<AppSortItemParcel>,
    val localeTag: String,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        items = buildList {
            parcel.readTypedList(this, AppSortItemParcel.CREATOR)
        },
        localeTag = requireNotNull(parcel.readString()),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeTypedList(items)
        parcel.writeString(localeTag)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<AppSortRequestParcel> {
        const val MAX_ITEMS = 2_000

        override fun createFromParcel(parcel: Parcel): AppSortRequestParcel =
            AppSortRequestParcel(parcel)

        override fun newArray(size: Int): Array<AppSortRequestParcel?> =
            arrayOfNulls(size)
    }
}
