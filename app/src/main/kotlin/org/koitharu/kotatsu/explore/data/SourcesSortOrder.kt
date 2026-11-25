package org.koitharu.kotatsu.explore.data

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

enum class SourcesSortOrder(
	@StringRes val titleResId: Int,
) {
	ALPHABETIC(R.string.by_name),
	LAST_USED(R.string.last_used),
}
