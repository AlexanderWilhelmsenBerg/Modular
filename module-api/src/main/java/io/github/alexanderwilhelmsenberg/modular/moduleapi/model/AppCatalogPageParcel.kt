package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

data class AppCatalogPageParcel(
    val status: Int,
    val generation: Long,
    val totalApps: Int,
    val offset: Int,
    val nextOffset: Int,
    val hasMore: Boolean,
    val apps: List<LaunchableAppParcel>,
    val error: String?,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        status = parcel.readInt(),
        generation = parcel.readLong(),
        totalApps = parcel.readInt(),
        offset = parcel.readInt(),
        nextOffset = parcel.readInt(),
        hasMore = parcel.readInt() != 0,
        apps = buildList {
            parcel.readTypedList(this, LaunchableAppParcel.CREATOR)
        },
        error = parcel.readString(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(status)
        parcel.writeLong(generation)
        parcel.writeInt(totalApps)
        parcel.writeInt(offset)
        parcel.writeInt(nextOffset)
        parcel.writeInt(if (hasMore) 1 else 0)
        parcel.writeTypedList(apps)
        parcel.writeString(error)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<AppCatalogPageParcel> {
        const val STATUS_OK = 0
        const val STATUS_STALE_GENERATION = 1
        const val STATUS_INVALID_ARGUMENT = 2

        override fun createFromParcel(parcel: Parcel): AppCatalogPageParcel =
            AppCatalogPageParcel(parcel)

        override fun newArray(size: Int): Array<AppCatalogPageParcel?> =
            arrayOfNulls(size)
    }
}
