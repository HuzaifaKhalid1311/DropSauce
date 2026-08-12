package org.koitharu.kotatsu.core.prefs

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

/**
 * A single button (or the slider) of the reader's bottom bar. The user picks which ones are shown
 * and in which order — see the "Reader controls in bottom bar" settings screen. Storage is an
 * ordered list, so the declaration order here only matters as the fallback for controls that are
 * turned on later.
 */
enum class ReaderControl(
	@StringRes val titleResId: Int,
	@DrawableRes val iconResId: Int,
) {

	PREV_CHAPTER(R.string.prev_chapter, R.drawable.ic_prev),
	NEXT_CHAPTER(R.string.next_chapter, R.drawable.ic_action_skip),
	SLIDER(R.string.pages_slider, R.drawable.ic_slider),
	PAGES_SHEET(R.string.chapters_and_pages, R.drawable.ic_grid),
	SCREEN_ROTATION(R.string.screen_orientation, R.drawable.ic_screen_rotation),
	SAVE_PAGE(R.string.save_page, R.drawable.ic_save),
	TIMER(R.string.automatic_scroll, R.drawable.ic_timer),
	BOOKMARK(R.string.bookmark_add, R.drawable.ic_bookmark);

	companion object {

		val DEFAULT: List<ReaderControl> = listOf(PREV_CHAPTER, SLIDER, NEXT_CHAPTER, PAGES_SHEET)
	}
}
