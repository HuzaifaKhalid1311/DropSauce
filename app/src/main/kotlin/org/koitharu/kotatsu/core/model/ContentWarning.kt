package org.koitharu.kotatsu.core.model

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

/**
 * How adult an extension is, as Mihon models it: [MIXED] extensions carry both safe and 18+ titles,
 * so for them it's each manga's own rating that decides, while [NSFW] ones are 18+ throughout.
 */
enum class ContentWarning(
	@StringRes val titleResId: Int,
	@ColorRes val colorResId: Int,
	/** Label of the extension filter that shows everything up to this level. */
	@StringRes val filterTitleResId: Int,
) {

	SAFE(R.string.sfw, R.color.common_green, R.string.content_filter_sfw),
	MIXED(R.string.content_warning_mixed, R.color.content_warning_mixed, R.string.content_filter_mixed),
	NSFW(R.string.nsfw, R.color.common_red, R.string.content_filter_all),
}
