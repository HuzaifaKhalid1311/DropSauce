package org.koitharu.kotatsu.favourites.domain.model

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

/**
 * Order of the favourite categories. [RANDOM] is the user's own drag order (`sort_key`); the others
 * are applied on read, so new, renamed and growing categories land in place. Entry names are persisted.
 */
enum class CategoriesOrder(
	val type: Type,
	val isAscending: Boolean,
) {

	RANDOM(Type.RANDOM, true),
	ALPHABETIC(Type.NAME, true),
	ALPHABETIC_REVERSE(Type.NAME, false),
	SIZE_ASC(Type.SIZE, true),
	SIZE(Type.SIZE, false),
	OLDEST(Type.CREATED, true),
	NEWEST(Type.CREATED, false),
	;

	enum class Type(
		@StringRes val titleResId: Int,
	) {

		RANDOM(R.string.random),
		NAME(R.string.by_name),
		SIZE(R.string.number_of_items),
		CREATED(R.string.date_created),
		;

		fun toOrder(isAscending: Boolean): CategoriesOrder = if (this == RANDOM) {
			CategoriesOrder.RANDOM // has no direction
		} else {
			CategoriesOrder.entries.first { it.type == this && it.isAscending == isAscending }
		}
	}
}
