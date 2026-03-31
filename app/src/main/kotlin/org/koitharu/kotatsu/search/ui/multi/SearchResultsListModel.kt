package org.haziffe.dropsauce.search.ui.multi

import android.content.Context
import androidx.annotation.StringRes
import org.haziffe.dropsauce.core.model.getTitle
import org.haziffe.dropsauce.list.ui.ListModelDiffCallback
import org.haziffe.dropsauce.list.ui.model.ListModel
import org.haziffe.dropsauce.list.ui.model.MangaListModel
import org.haziffe.dropsauce.parsers.model.MangaListFilter
import org.haziffe.dropsauce.parsers.model.MangaSource
import org.haziffe.dropsauce.parsers.model.SortOrder

data class SearchResultsListModel(
	@StringRes val titleResId: Int,
	val source: MangaSource,
	val listFilter: MangaListFilter?,
	val sortOrder: SortOrder?,
	val list: List<MangaListModel>,
	val error: Throwable?,
) : ListModel {

	fun getTitle(context: Context): String = if (titleResId != 0) {
		context.getString(titleResId)
	} else {
		source.getTitle(context)
	}

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is SearchResultsListModel && source == other.source && titleResId == other.titleResId
	}

	override fun getChangePayload(previousState: ListModel): Any? {
		return if (previousState is SearchResultsListModel && previousState.list != list) {
			ListModelDiffCallback.PAYLOAD_NESTED_LIST_CHANGED
		} else {
			super.getChangePayload(previousState)
		}
	}
}
