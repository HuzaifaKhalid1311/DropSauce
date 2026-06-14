package org.koitharu.kotatsu.details.ui

import android.content.Context
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.get
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialSplitButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.setOptionalIconsVisibleCompat
import org.koitharu.kotatsu.details.ui.model.HistoryInfo

class ReadButtonDelegate(
	private val splitButton: MaterialSplitButton,
	private val viewModel: DetailsViewModel,
	private val router: AppRouter,
) : View.OnClickListener, PopupMenu.OnMenuItemClickListener, PopupMenu.OnDismissListener {

	private val buttonRead = splitButton[0] as MaterialButton
	private val buttonMenu = splitButton[1] as MaterialButton

	private val context: Context
		get() = buttonRead.context

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_read -> openReader(isIncognitoMode = false)
			R.id.button_read_menu -> showMenu()
		}
	}

	override fun onMenuItemClick(item: MenuItem): Boolean {
		when (item.itemId) {
			R.id.action_incognito -> openReader(isIncognitoMode = true)
			R.id.action_forget -> viewModel.removeFromHistory()
			R.id.action_downloaded -> {
				viewModel.isDownloadedOnly.value = !item.isChecked
			}
			R.id.action_reversed -> {
				viewModel.setChaptersReversed(!item.isChecked)
			}
			R.id.action_grid_view -> {
				viewModel.setChaptersInGridView(!item.isChecked)
			}

			else -> return false
		}
		return true
	}

	override fun onDismiss(menu: PopupMenu?) {
		buttonMenu.isChecked = false
	}

	fun attach(lifecycleOwner: LifecycleOwner) {
		buttonRead.setOnClickListener(this)
		buttonMenu.setOnClickListener(this)
		combine(viewModel.isLoading, viewModel.historyInfo, ::Pair)
			.observe(lifecycleOwner) { (isLoading, historyInfo) ->
				onHistoryChanged(isLoading, historyInfo)
			}
	}

	private fun showMenu() {
		val menu = PopupMenu(context, buttonMenu)
		menu.inflate(R.menu.popup_read)
		prepareMenu(menu.menu)
		menu.setOnMenuItemClickListener(this)
		menu.menu.setOptionalIconsVisibleCompat(true)
		menu.setForceShowIcon(true)
		menu.setOnDismissListener(this)
		if (menu.menu.hasVisibleItems()) {
			buttonMenu.isChecked = true
			menu.show()
		} else {
			buttonMenu.isChecked = false
		}
	}

	private fun prepareMenu(menu: Menu) {
		val history = viewModel.historyInfo.value
		menu.setGroupCheckable(R.id.group_chapter_options, true, false)
		menu.findItem(R.id.action_incognito)?.isVisible = !history.isIncognitoMode
		menu.findItem(R.id.action_forget)?.isVisible = history.history != null
		menu.findItem(R.id.action_downloaded)?.let { menuItem ->
			menuItem.isVisible = viewModel.mangaDetails.value?.local != null
			menuItem.isChecked = viewModel.isDownloadedOnly.value
		}
		menu.findItem(R.id.action_reversed)?.isChecked = viewModel.isChaptersReversed.value
		menu.findItem(R.id.action_grid_view)?.isChecked = viewModel.isChaptersInGridView.value
	}

	private fun openReader(isIncognitoMode: Boolean) {
		val manga = viewModel.getMangaOrNull() ?: return
		if (viewModel.historyInfo.value.isChapterMissing) {
			Snackbar.make(buttonRead, R.string.chapter_is_missing, Snackbar.LENGTH_LONG)
				.setAction(R.string.remove_from_history) { viewModel.removeFromHistory() }
				.show()
		} else {
			val intentBuilder = ReaderIntent.Builder(context)
				.manga(manga)
				.branch(viewModel.selectedBranchValue)
			if (isIncognitoMode) {
				intentBuilder.incognito()
			}
			router.openReader(intentBuilder.build())
			if (isIncognitoMode) {
				Toast.makeText(context, R.string.incognito_mode, Toast.LENGTH_SHORT).show()
			}
		}
	}

	private fun onHistoryChanged(isLoading: Boolean, info: HistoryInfo) {
		val isChaptersLoading = isLoading && (info.totalChapters <= 0 || info.isChapterMissing)
		buttonRead.setText(
			when {
				isChaptersLoading -> R.string.loading_
				info.isIncognitoMode -> R.string.incognito
				info.canContinue -> R.string._continue
				else -> R.string.read
			},
		)
		splitButton.isEnabled = !isChaptersLoading && info.isValid
	}

}
