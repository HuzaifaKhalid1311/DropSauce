package org.koitharu.kotatsu.list.domain

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.parsers.util.find

/**
 * A sort order is a [Type] plus a direction. The enum stays flat because its entry names are what
 * gets persisted (`favourite_categories.sort_order` and the history/favorites prefs), so renaming or
 * reordering entries would silently reset users' saved sorts.
 */
enum class ListSortOrder(
	val type: Type,
	val isAscending: Boolean,
) {

	ALPHABETIC(Type.ALPHABETICAL, true),
	ALPHABETIC_REVERSE(Type.ALPHABETICAL, false),

	TOTAL_CHAPTERS_ASC(Type.TOTAL_CHAPTERS, true),
	TOTAL_CHAPTERS(Type.TOTAL_CHAPTERS, false),

	LONG_AGO_READ(Type.LAST_READ, true),
	LAST_READ(Type.LAST_READ, false),

	UNREAD_COUNT_ASC(Type.UNREAD_COUNT, true),
	UNREAD_COUNT(Type.UNREAD_COUNT, false),

	LATEST_CHAPTER_ASC(Type.LATEST_CHAPTER, true),
	LATEST_CHAPTER(Type.LATEST_CHAPTER, false),

	OLDEST(Type.DATE_ADDED, true),
	NEWEST(Type.DATE_ADDED, false),

	UNREAD(Type.PROGRESS, true),
	PROGRESS(Type.PROGRESS, false),

	NEW_CHAPTERS_ASC(Type.NEW_CHAPTERS, true),
	NEW_CHAPTERS(Type.NEW_CHAPTERS, false),
	;

	@get:StringRes
	val titleResId: Int
		get() = type.titleResId

	fun isGroupingSupported() = type == Type.LAST_READ || type == Type.DATE_ADDED || type == Type.PROGRESS

	/**
	 * Sortable columns, in the order they are shown to the user: Mihon's set first, then the ones
	 * only DropSauce has.
	 */
	enum class Type(
		@StringRes val titleResId: Int,
	) {

		ALPHABETICAL(R.string.by_name),
		TOTAL_CHAPTERS(R.string.total_chapters),
		LAST_READ(R.string.last_read),
		UNREAD_COUNT(R.string.sort_unread_count),
		LATEST_CHAPTER(R.string.latest_chapter),
		DATE_ADDED(R.string.order_added),
		PROGRESS(R.string.progress),
		NEW_CHAPTERS(R.string.new_chapters),
		;

		fun toSortOrder(isAscending: Boolean) = ListSortOrder.entries
			.first { it.type == this && it.isAscending == isAscending }
	}

	companion object {

		val HISTORY: List<Type> = Type.entries - Type.DATE_ADDED

		val FAVORITES: List<Type> = Type.entries

		operator fun invoke(value: String, fallback: ListSortOrder) = entries.find(value) ?: fallback
	}
}
