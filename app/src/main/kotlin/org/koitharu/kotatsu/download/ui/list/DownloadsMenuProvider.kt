package org.koitharu.kotatsu.download.ui.list

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import androidx.fragment.app.FragmentActivity
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.dialog.showConfirmDialog

class DownloadsMenuProvider(
	private val activity: FragmentActivity,
	private val viewModel: DownloadsViewModel,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_downloads, menu)
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		when (menuItem.itemId) {
			R.id.action_pause -> viewModel.pauseAll()
			R.id.action_resume -> viewModel.resumeAll()
			R.id.action_cancel_all -> confirmCancelAll()
			R.id.action_remove_completed -> confirmRemoveCompleted()
			R.id.action_settings -> activity.router.openDownloadsSetting()
			else -> return false
		}
		return true
	}

	override fun onPrepareMenu(menu: Menu) {
		super.onPrepareMenu(menu)
		menu.findItem(R.id.action_pause)?.isVisible = viewModel.hasActiveWorks.value == true
		menu.findItem(R.id.action_resume)?.isVisible = viewModel.hasPausedWorks.value == true
		menu.findItem(R.id.action_cancel_all)?.isVisible = viewModel.hasCancellableWorks.value == true
	}

	private fun confirmCancelAll() {
		showConfirmDialog(
			context = activity,
			icon = R.drawable.ic_cancel_multiple,
			title = activity.getString(R.string.cancel_all),
			message = activity.getString(R.string.cancel_all_downloads_confirm),
			confirmLabel = activity.getString(R.string.confirm),
			destructive = true,
		) { viewModel.cancelAll() }
	}

	private fun confirmRemoveCompleted() {
		showConfirmDialog(
			context = activity,
			icon = R.drawable.ic_clear_all,
			title = activity.getString(R.string.remove_completed),
			message = activity.getString(R.string.remove_completed_downloads_confirm),
			confirmLabel = activity.getString(R.string.clear),
			destructive = true,
		) { viewModel.removeCompleted() }
	}
}
