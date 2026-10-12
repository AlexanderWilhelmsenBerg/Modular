package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

data class AppCatalogInfoParcel(
    val generation: Long,
    val totalApps: Int,
    val profiles: List<AppProfileParcel>,
    val refreshing: Boolean,
    val error: String?,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        generation = parcel.readLong(),
        totalApps = parcel.readInt(),
        profiles = buildList {
            parcel.readTypedList(this, AppProfileParcel.CREATOR)
        },
        refreshing = parcel.readInt() != 0,
        error = parcel.readString(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(generation)
        parcel.writeInt(totalApps)
        parcel.writeTypedList(profiles)
        parcel.writeInt(if (refreshing) 1 else 0)
        parcel.writeString(error)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<AppCatalogInfoParcel> {
        override fun createFromParcel(parcel: Parcel): AppCatalogInfoParcel =
            AppCatalogInfoParcel(parcel)

        override fun newArray(size: Int): Array<AppCatalogInfoParcel?> =
            arrayOfNulls(size)
    }
}
