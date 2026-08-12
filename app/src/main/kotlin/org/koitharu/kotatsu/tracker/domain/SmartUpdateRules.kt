package org.koitharu.kotatsu.tracker.domain

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.model.MangaState

/**
 * Why the tracker would not spend a network request on an entry, or `null` if it would check it.
 * The actual filtering happens in `TracksDao.findAllForChecking` — this mirrors it for the
 * tracker debug screen, so keep the two in sync.
 */
@StringRes
fun smartUpdateSkipReason(
	rules: Set<String>,
	state: MangaState?,
	hasProgress: Boolean,
	newChapters: Int,
): Int? = when {
	rules.isEmpty() -> null
	AppSettings.SMART_UPDATE_SKIP_COMPLETED in rules && state == MangaState.FINISHED ->
		R.string.smart_update_skip_completed

	AppSettings.SMART_UPDATE_SKIP_UNSTARTED in rules && !hasProgress ->
		R.string.smart_update_skip_unstarted

	AppSettings.SMART_UPDATE_SKIP_UNREAD in rules && newChapters > 0 ->
		R.string.smart_update_skip_unread

	else -> null
}
