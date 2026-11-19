package org.koitharu.kotatsu.backup.local.ui.restore

import androidx.annotation.StringRes
import org.koitharu.kotatsu.backup.local.domain.BackupSection
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.model.ListModel

data class BackupSectionModel(
	val section: BackupSection,
	val isChecked: Boolean,
	val isEnabled: Boolean,
	val itemCount: Int? = null,
) : ListModel {

	@get:StringRes
	val titleResId: Int
		get() = section.titleResId

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is BackupSectionModel && other.section == section
	}

	override fun getChangePayload(previousState: ListModel): Any? {
		if (previousState !is BackupSectionModel) {
			return null
		}
		return when {
			previousState.isEnabled != isEnabled || previousState.itemCount != itemCount -> ListModelDiffCallback.PAYLOAD_ANYTHING_CHANGED
			previousState.isChecked != isChecked -> ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED
			else -> super.getChangePayload(previousState)
		}
	}
}
