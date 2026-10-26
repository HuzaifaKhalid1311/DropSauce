package org.koitharu.kotatsu.core.model

import android.content.Context
import android.text.style.ForegroundColorSpan
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import org.koitharu.kotatsu.R

/**
 * How adult an extension is, as Mihon models it: [MIXED] extensions carry both safe and 18+ titles,
 * so for them it's each manga's own rating that decides, while [NSFW] ones are 18+ throughout.
 */
enum class ContentWarning(
	/** Shown at the end of an extension's description, in [tagColorRes]; SFW ones stay unmarked. */
	@StringRes val tagResId: Int?,
	@ColorRes val tagColorRes: Int?,
	/** Label of the extension filter that shows everything up to this level. */
	@StringRes val filterTitleResId: Int,
) {

	SAFE(null, null, R.string.content_filter_sfw),
	MIXED(R.string.content_warning_mixed, R.color.common_yellow, R.string.content_filter_mixed),
	NSFW(R.string.nsfw, R.color.common_red, R.string.content_filter_all),
}

/** [description] + " • Mixed" / " • 18+", the tag tinted yellow or red; SFW ones get nothing. */
fun ContentWarning?.appendTag(context: Context, description: CharSequence): CharSequence {
	val warning = this ?: return description
	val tag = warning.tagResId ?: return description
	val color = warning.tagColorRes ?: return description
	return buildSpannedString {
		append(description)
		append(" • ")
		inSpans(ForegroundColorSpan(ContextCompat.getColor(context, color))) {
			append(context.getString(tag))
		}
	}
}
