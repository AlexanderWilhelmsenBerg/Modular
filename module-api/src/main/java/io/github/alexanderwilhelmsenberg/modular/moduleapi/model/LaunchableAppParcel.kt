package io.github.alexanderwilhelmsenberg.modular.moduleapi.model

import android.os.Parcel
import android.os.Parcelable

data class LaunchableAppParcel(
    val key: AppKeyParcel,
    val label: String,
    val firstInstallTime: Long,
    val enabled: Boolean,
    val loadingProgress: Float?,
) : Parcelable {
    @Suppress("DEPRECATION")
    private constructor(parcel: Parcel) : this(
        key = requireNotNull(
            parcel.readParcelable<AppKeyParcel>(
                AppKeyParcel::class.java.classLoader,
            ),
        ),
        label = requireNotNull(parcel.readString()),
        firstInstallTime = parcel.readLong(),
        enabled = parcel.readInt() != 0,
        loadingProgress = if (parcel.readInt() != 0) parcel.readFloat() else null,
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(key, flags)
        parcel.writeString(label)
        parcel.writeLong(firstInstallTime)
        parcel.writeInt(if (enabled) 1 else 0)
        if (loadingProgress != null) {
            parcel.writeInt(1)
            parcel.writeFloat(loadingProgress)
        } else {
            parcel.writeInt(0)
        }
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<LaunchableAppParcel> {
        override fun createFromParcel(parcel: Parcel): LaunchableAppParcel =
            LaunchableAppParcel(parcel)

        override fun newArray(size: Int): Array<LaunchableAppParcel?> =
            arrayOfNulls(size)
    }
}
