package org.koitharu.kotatsu.list.ui.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings

data class InfoModel(
	val key: String,
	@StringRes val title: Int,
	@StringRes val text: Int,
	@DrawableRes val icon: Int,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is InfoModel && other.key == key
	}
}

/** The "progress is not saved" notice shown on every list screen while incognito mode is on. */
val incognitoInfo = InfoModel(
	key = AppSettings.KEY_INCOGNITO_MODE,
	title = R.string.incognito_mode,
	text = R.string.incognito_mode_hint,
	icon = R.drawable.ic_incognito,
)
