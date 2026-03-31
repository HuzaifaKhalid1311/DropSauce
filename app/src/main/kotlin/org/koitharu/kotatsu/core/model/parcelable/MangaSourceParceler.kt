package org.haziffe.dropsauce.core.model.parcelable

import android.os.Parcel
import kotlinx.parcelize.Parceler
import org.haziffe.dropsauce.core.model.MangaSource
import org.haziffe.dropsauce.parsers.model.MangaSource

class MangaSourceParceler : Parceler<MangaSource> {

	override fun create(parcel: Parcel): MangaSource = MangaSource(parcel.readString())

	override fun MangaSource.write(parcel: Parcel, flags: Int) {
		parcel.writeString(name)
	}
}
