package org.haziffe.dropsauce.details.ui.pager.pages

import org.haziffe.dropsauce.list.ui.model.ListModel
import org.haziffe.dropsauce.reader.ui.pager.ReaderPage

data class PageThumbnail(
	val isCurrent: Boolean,
	val page: ReaderPage,
) : ListModel {

	val number
		get() = page.index + 1

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is PageThumbnail && page == other.page
	}
}
