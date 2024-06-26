package me.magnum.melonds.parcelables

import android.os.Parcel
import android.os.Parcelable
import me.magnum.melonds.domain.model.rom.config.RomConfig
import me.magnum.melonds.domain.model.rom.config.RuntimeConsoleType
import me.magnum.melonds.domain.model.rom.config.RuntimeMicSource
import me.magnum.melonds.extensions.parcelable
import java.util.UUID

class RomConfigParcelable : Parcelable {
    val romConfig: RomConfig

    constructor(romConfig: RomConfig) {
        this.romConfig = romConfig
    }

    private constructor(parcel: Parcel) {
        romConfig = RomConfig(
            runtimeConsoleType = RuntimeConsoleType.entries[parcel.readInt()],
            runtimeMicSource = RuntimeMicSource.entries[parcel.readInt()],
            layoutId = parcel.readString()?.let { UUID.fromString(it) },
            gbaSlotConfig = parcel.parcelable<RomGbaSlotConfigParcelable>()!!.gbaSlotConfig,
        )
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(romConfig.runtimeConsoleType.ordinal)
        dest.writeInt(romConfig.runtimeMicSource.ordinal)
        dest.writeString(romConfig.layoutId?.toString())
        dest.writeParcelable(RomGbaSlotConfigParcelable(romConfig.gbaSlotConfig), 0)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<RomConfigParcelable> {
        override fun createFromParcel(parcel: Parcel): RomConfigParcelable {
            return RomConfigParcelable(parcel)
        }

        override fun newArray(size: Int): Array<RomConfigParcelable?> {
            return arrayOfNulls(size)
        }
    }
}