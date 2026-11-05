package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

data class SurfaceDocumentParcel(
    val schemaVersion: Int,
    val revision: Long,
    val root: SurfaceNodeParcel,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        schemaVersion = parcel.readInt(),
        revision = parcel.readLong(),
        root = requireNotNull(
            parcel.readParcelable(
                SurfaceNodeParcel::class.java.classLoader,
            ),
        ),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(schemaVersion)
        parcel.writeLong(revision)
        parcel.writeParcelable(root, flags)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<SurfaceDocumentParcel> {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_NODES = 128
        const val MAX_DEPTH = 12
        const val MAX_TEXT_LENGTH = 512
        const val MAX_TOTAL_TEXT_CHARS = 8_192
        const val MAX_ID_LENGTH = 64
        const val MAX_ACTION_ID_LENGTH = 96

        override fun createFromParcel(parcel: Parcel): SurfaceDocumentParcel =
            SurfaceDocumentParcel(parcel)

        override fun newArray(size: Int): Array<SurfaceDocumentParcel?> =
            arrayOfNulls(size)
    }
}
