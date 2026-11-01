package org.koitharu.kotatsu.reader.ui.colorfilter

import android.view.View
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.showConfirmDialog
import org.koitharu.kotatsu.core.ui.util.PredictiveBackCallback
import org.koitharu.kotatsu.core.ui.util.predictiveBackTarget
import org.koitharu.kotatsu.core.util.ext.call

class ColorFilterConfigBackPressedDispatcher(
	private val activity: ColorFilterConfigActivity,
	private val viewModel: ColorFilterConfigViewModel,
) : PredictiveBackCallback(true) {

	override val backPreviewTarget: View?
		get() = activity.predictiveBackTarget()

	override fun onBackConfirmed() {
		if (viewModel.isChanged) {
			showConfirmation()
		} else {
			viewModel.onDismiss.call(Unit)
		}
	}

	private fun showConfirmation() {
		showConfirmDialog(
			context = activity,
			icon = R.drawable.ic_tune,
			title = activity.getString(R.string.color_correction),
			message = activity.getString(R.string.text_unsaved_changes_prompt),
			confirmLabel = activity.getString(R.string.save),
			secondaryLabel = activity.getString(R.string.discard),
			onSecondary = { viewModel.onDismiss.call(Unit) },
			onConfirm = activity::showSaveConfirmation,
		)
	}
}
