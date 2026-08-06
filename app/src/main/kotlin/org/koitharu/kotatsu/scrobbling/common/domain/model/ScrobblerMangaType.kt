package org.koitharu.kotatsu.scrobbling.common.domain.model

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

/**
 * Which kind of entry the scrobbler search should return. Trackers keep prose and comics in the same
 * catalogue, so linking a novel used to mean scrolling past every manga with a similar title.
 *
 * Most services filter this server-side; MAL has no such query parameter and filters its own pages,
 * and Shikimori keeps novels on a separate endpoint entirely, so it ignores this and returns manga.
 */
enum class ScrobblerMangaType(@StringRes val titleResId: Int) {

	MANGA(R.string.content_type_manga),
	NOVEL(R.string.content_type_novel),
	;

	val isNovel: Boolean
		get() = this == NOVEL
}
