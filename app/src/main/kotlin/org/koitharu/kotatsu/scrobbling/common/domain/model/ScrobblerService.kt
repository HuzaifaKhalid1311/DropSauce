package org.koitharu.kotatsu.scrobbling.common.domain.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

enum class ScrobblerService(
	val id: Int,
	@StringRes val titleResId: Int,
	@DrawableRes val iconResId: Int,
	/** A full-color bitmap logo, which tinting would flatten into a silhouette. */
	val isBitmapIcon: Boolean = false,
) {

	SHIKIMORI(1, R.string.shikimori, R.drawable.ic_shikimori),
	ANILIST(2, R.string.anilist, R.drawable.ic_anilist),
	MAL(3, R.string.mal, R.drawable.ic_mal),
	KITSU(4, R.string.kitsu, R.drawable.ic_kitsu),
	MANGABAKA(5, R.string.mangabaka, R.drawable.ic_mangabaka, isBitmapIcon = true),
	COMICK(6, R.string.comick, R.drawable.ic_comick, isBitmapIcon = true),
}
