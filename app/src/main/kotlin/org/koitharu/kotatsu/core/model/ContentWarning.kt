package org.koitharu.kotatsu.core.model

import android.content.Context
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

/**
 * How adult an extension is, as Mihon models it: [MIXED] extensions carry both safe and 18+ titles,
 * so for them it's each manga's own rating that decides, while [NSFW] ones are 18+ throughout.
 */
enum class ContentWarning(
	/** Shown at the end of an extension's description; SFW ones stay unmarked. */
	@StringRes val tagResId: Int?,
	/** Label of the extension filter that shows everything up to this level. */
	@StringRes val filterTitleResId: Int,
) {

	SAFE(null, R.string.content_filter_sfw),
	MIXED(R.string.content_warning_mixed, R.string.content_filter_mixed),
	NSFW(R.string.nsfw, R.string.content_filter_all),
}

/** " • Mixed" / " • 18+" to append to an extension's description, or nothing for SFW. */
fun ContentWarning.descriptionSuffix(context: Context): String =
	tagResId?.let { " • " + context.getString(it) }.orEmpty()
