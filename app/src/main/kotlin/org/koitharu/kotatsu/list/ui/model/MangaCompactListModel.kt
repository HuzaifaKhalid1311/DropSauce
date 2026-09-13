package org.koitharu.kotatsu.list.ui.model

import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback.Companion.PAYLOAD_PROGRESS_CHANGED
import org.koitharu.kotatsu.parsers.model.Manga

data class MangaCompactListModel(
	override val manga: Manga,
	override val override: MangaOverride?,
	val progress: ReadingProgress?,
	override val counter: Int,
	val isPinned: Boolean = false,
) : MangaListModel() {

	override fun getChangePayload(previousState: ListModel): Any? = when {
		previousState !is MangaCompactListModel || previousState.manga != manga -> null
		previousState.progress != progress -> PAYLOAD_PROGRESS_CHANGED
		else -> super.getChangePayload(previousState)
	}
}
