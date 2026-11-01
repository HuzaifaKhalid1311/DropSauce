package org.koitharu.kotatsu.search.ui.suggestion

import android.content.Context
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.showConfirmDialog
import org.koitharu.kotatsu.core.util.ext.setOptionalIconsVisibleCompat

class SearchSuggestionMenuProvider(
	private val context: Context,
	private val viewModel: SearchSuggestionViewModel,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_search_suggestion, menu)
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		return when (menuItem.itemId) {
			R.id.action_clear -> {
				clearSearchHistory()
				true
			}

			else -> false
		}
	}

	override fun onPrepareMenu(menu: Menu) {
		super.onPrepareMenu(menu)
		menu.setOptionalIconsVisibleCompat(true)
	}

	private fun clearSearchHistory() {
		showConfirmDialog(
			context = context,
			icon = R.drawable.ic_clear_all,
			title = context.getString(R.string.clear_search_history),
			message = context.getString(R.string.text_clear_search_history_prompt),
			confirmLabel = context.getString(R.string.clear),
			destructive = true,
		) {
			viewModel.clearSearchHistory()
		}
	}
}
