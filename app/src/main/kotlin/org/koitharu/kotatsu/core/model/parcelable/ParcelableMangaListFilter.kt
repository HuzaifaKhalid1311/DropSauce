package org.haziffe.dropsauce.core.model.parcelable

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import org.haziffe.dropsauce.core.util.ext.readEnumSet
import org.haziffe.dropsauce.core.util.ext.readParcelableCompat
import org.haziffe.dropsauce.core.util.ext.readSerializableCompat
import org.haziffe.dropsauce.core.util.ext.writeEnumSet
import org.haziffe.dropsauce.parsers.model.ContentRating
import org.haziffe.dropsauce.parsers.model.ContentType
import org.haziffe.dropsauce.parsers.model.Demographic
import org.haziffe.dropsauce.parsers.model.MangaListFilter
import org.haziffe.dropsauce.parsers.model.MangaState

object MangaListFilterParceler : Parceler<MangaListFilter> {

	override fun MangaListFilter.write(parcel: Parcel, flags: Int) {
		parcel.writeString(query)
		parcel.writeParcelable(ParcelableMangaTags(tags), 0)
		parcel.writeParcelable(ParcelableMangaTags(tagsExclude), 0)
		parcel.writeSerializable(locale)
		parcel.writeSerializable(originalLocale)
		parcel.writeEnumSet(states)
		parcel.writeEnumSet(contentRating)
		parcel.writeEnumSet(types)
		parcel.writeEnumSet(demographics)
		parcel.writeInt(year)
		parcel.writeInt(yearFrom)
		parcel.writeInt(yearTo)
		parcel.writeString(author)
	}

	override fun create(parcel: Parcel) = MangaListFilter(
		query = parcel.readString(),
		tags = parcel.readParcelableCompat<ParcelableMangaTags>()?.tags.orEmpty(),
		tagsExclude = parcel.readParcelableCompat<ParcelableMangaTags>()?.tags.orEmpty(),
		locale = parcel.readSerializableCompat(),
		originalLocale = parcel.readSerializableCompat(),
		states = parcel.readEnumSet<MangaState>().orEmpty(),
		contentRating = parcel.readEnumSet<ContentRating>().orEmpty(),
		types = parcel.readEnumSet<ContentType>().orEmpty(),
		demographics = parcel.readEnumSet<Demographic>().orEmpty(),
		year = parcel.readInt(),
		yearFrom = parcel.readInt(),
		yearTo = parcel.readInt(),
		author = parcel.readString(),
	)
}

@Parcelize
@TypeParceler<MangaListFilter, MangaListFilterParceler>
data class ParcelableMangaListFilter(val filter: MangaListFilter) : Parcelable
