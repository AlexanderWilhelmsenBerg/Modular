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
    /**
     * Decode bounds are enforced while reading the typed-list envelope.
     * Post-unmarshalling validation alone cannot prevent excessive recursion
     * or list allocation triggered by a malformed remote Parcelable.
     */
    private class DecodeBudget(
        var remainingNodes: Int = SurfaceDocumentParcel.MAX_NODES,
    )

    private constructor(parcel: Parcel) : this(
        readBoundedNode(parcel, DecodeBudget(), depth = 1),
    )

    private constructor(node: SurfaceNodeParcel) : this(
        id = node.id,
        type = node.type,
        text = node.text,
        textStyle = node.textStyle,
        actionId = node.actionId,
        children = node.children,
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

        private fun readBoundedNode(
            parcel: Parcel,
            budget: DecodeBudget,
            depth: Int,
        ): SurfaceNodeParcel {
            require(depth <= SurfaceDocumentParcel.MAX_DEPTH) {
                "Surface parcel exceeds maximum depth"
            }
            require(budget.remainingNodes > 0) {
                "Surface parcel exceeds maximum node count"
            }
            budget.remainingNodes -= 1

            val id = requireNotNull(parcel.readString()) {
                "Surface parcel node has no id"
            }
            val type = parcel.readInt()
            val text = parcel.readString()
            val textStyle = parcel.readInt()
            val actionId = parcel.readString()

            // writeTypedList writes a count followed by presence flags and
            // Parcelable bodies. Check count BEFORE allocating the list.
            val childCount = parcel.readInt()
            require(childCount in 0..budget.remainingNodes) {
                "Surface parcel has invalid or excessive child count"
            }

            val children = ArrayList<SurfaceNodeParcel>(childCount)
            repeat(childCount) {
                require(parcel.readInt() == 1) {
                    "Surface parcel contains a null or invalid child"
                }
                children += readBoundedNode(
                    parcel = parcel,
                    budget = budget,
                    depth = depth + 1,
                )
            }

            return SurfaceNodeParcel(
                id = id,
                type = type,
                text = text,
                textStyle = textStyle,
                actionId = actionId,
                children = children,
            )
        }

        override fun createFromParcel(parcel: Parcel): SurfaceNodeParcel =
            SurfaceNodeParcel(parcel)

        override fun newArray(size: Int): Array<SurfaceNodeParcel?> =
            arrayOfNulls(size)
    }
}
