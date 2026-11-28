package org.koitharu.kotatsu.tracker.ui.feed.model

import org.koitharu.kotatsu.core.model.withOverride
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.tracker.domain.model.TrackingLogItem

data class FeedItem(
	val id: Long,
	private val override: MangaOverride?,
	val manga: Manga,
	val chapters: List<TrackingLogItem.Chapter>,
	val isNew: Boolean,
	val groupPosition: GroupPosition = GroupPosition.SINGLE,
	val isExpanded: Boolean = false,
) : ListModel {

	// Position within a date group, used to shape the segmented list background.
	enum class GroupPosition { SINGLE, FIRST, MIDDLE, LAST }

	val count: Int
		get() = chapters.size

	val imageUrl: String?
		get() = override?.coverUrl.ifNullOrEmpty { manga.coverUrl }

	val title: String
		get() = override?.title.ifNullOrEmpty { manga.title }

	fun toMangaWithOverride() = manga.withOverride(override)

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is FeedItem && other.id == id
	}
}
