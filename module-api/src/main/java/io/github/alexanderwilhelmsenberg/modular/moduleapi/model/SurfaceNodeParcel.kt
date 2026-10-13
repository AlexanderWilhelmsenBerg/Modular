package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

data class SurfaceNodeParcel(
    val id: String,
    val type: Int,
    val text: String? = null,
    val textStyle: Int = TEXT_STYLE_BODY,
    val actionId: String? = null,
    val children: List<SurfaceNodeParcel> = emptyList(),
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        id = requireNotNull(parcel.readString()),
        type = parcel.readInt(),
        text = parcel.readString(),
        textStyle = parcel.readInt(),
        actionId = parcel.readString(),
        children = buildList {
            parcel.readTypedList(this, CREATOR)
        },
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeInt(type)
        parcel.writeString(text)
        parcel.writeInt(textStyle)
        parcel.writeString(actionId)
        parcel.writeTypedList(children)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<SurfaceNodeParcel> {
        const val TYPE_COLUMN = 1
        const val TYPE_ROW = 2
        const val TYPE_TEXT = 3
        const val TYPE_BUTTON = 4
        const val TYPE_SPACER = 5

        const val TEXT_STYLE_BODY = 0
        const val TEXT_STYLE_TITLE = 1
        const val TEXT_STYLE_HEADLINE = 2

        override fun createFromParcel(parcel: Parcel): SurfaceNodeParcel =
            SurfaceNodeParcel(parcel)

        override fun newArray(size: Int): Array<SurfaceNodeParcel?> =
            arrayOfNulls(size)
    }
}
