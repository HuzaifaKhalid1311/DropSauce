package org.koitharu.kotatsu.tracker.ui.feed.model

import android.content.Context
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.model.DateTimeAgo
import org.koitharu.kotatsu.list.ui.model.ListModel

/** Plain date heading above a grouped day of feed updates. */
data class FeedSection(
	val key: String,
	val date: DateTimeAgo?,
) : ListModel {

	fun getTitle(context: Context): String = date?.format(context) ?: context.getString(R.string.unknown)

	override fun areItemsTheSame(other: ListModel): Boolean = other is FeedSection && other.key == key
}
