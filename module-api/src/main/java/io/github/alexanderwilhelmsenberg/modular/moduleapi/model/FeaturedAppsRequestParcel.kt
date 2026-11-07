package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

/** Current catalog snapshot for one featured read, bounded independently of Core's full catalog. */
data class FeaturedAppsRequestParcel(
    val requestId: String,
    val catalogRevision: Long,
    val localeTag: String,
    val items: List<AppSortItemParcel>,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        requestId = requireNotNull(parcel.readString()),
        catalogRevision = parcel.readLong(),
        localeTag = requireNotNull(parcel.readString()),
        items = parcel.readBoundedTypedList(AppSortItemParcel.CREATOR, MAX_ITEMS),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(requestId)
        parcel.writeLong(catalogRevision)
        parcel.writeString(localeTag)
        parcel.writeBoundedTypedList(items, MAX_ITEMS)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<FeaturedAppsRequestParcel> {
        const val MAX_ITEMS = FeaturedParcelBounds.MAX_CATALOG_ITEMS
        override fun createFromParcel(parcel: Parcel) = FeaturedAppsRequestParcel(parcel)
        override fun newArray(size: Int): Array<FeaturedAppsRequestParcel?> = arrayOfNulls(size)
    }
}
