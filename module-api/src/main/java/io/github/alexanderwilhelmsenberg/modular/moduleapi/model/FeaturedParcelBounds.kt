package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

/** Limits are part of modular.apps.featured/v1. */
object FeaturedParcelBounds {
    const val MAX_CATALOG_ITEMS = 2_000
    const val MAX_PINS = 50
    const val MAX_SUGGESTIONS = 20
    const val MAX_REQUEST_ID_LENGTH = 128
    const val MAX_LOCALE_TAG_LENGTH = 128
    const val MAX_LABEL_LENGTH = 512
    const val MAX_KEY_PART_LENGTH = 512
    const val MAX_MESSAGE_LENGTH = 512
}

internal fun <T : Parcelable> Parcel.readBoundedTypedList(
    creator: Parcelable.Creator<T>,
    maxSize: Int,
): List<T> {
    val size = readInt()
    require(size in 0..maxSize) { "Invalid featured list size" }
    return List(size) {
        requireNotNull(readTypedObject(creator)) { "Null featured list item" }
    }
}

internal fun <T : Parcelable> Parcel.writeBoundedTypedList(items: List<T>, maxSize: Int) {
    require(items.size <= maxSize) { "Featured list too large" }
    writeTypedList(items)
}
