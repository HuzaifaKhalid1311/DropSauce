package org.haziffe.dropsauce.suggestions.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.core.nav.router
import org.haziffe.dropsauce.core.ui.list.ListSelectionController
import org.haziffe.dropsauce.core.util.ext.addMenuProvider
import org.haziffe.dropsauce.databinding.FragmentListBinding
import org.haziffe.dropsauce.list.ui.MangaListFragment

class SuggestionsFragment : MangaListFragment() {

	override val viewModel by viewModels<SuggestionsViewModel>()
	override val isSwipeRefreshEnabled = false

	override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		addMenuProvider(SuggestionMenuProvider())
	}

	override fun onScrolledToEnd() = Unit

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu,
	): Boolean {
		menuInflater.inflate(R.menu.mode_remote, menu)
		return super.onCreateActionMode(controller, menuInflater, menu)
	}

	private inner class SuggestionMenuProvider : MenuProvider {

		override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
			menuInflater.inflate(R.menu.opt_suggestions, menu)
		}

		override fun onPrepareMenu(menu: Menu) {
			super.onPrepareMenu(menu)
			menu.findItem(R.id.action_settings_suggestions)?.isVisible =
				menu.findItem(R.id.action_settings) == null
		}

		override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
			R.id.action_update -> {
				viewModel.updateSuggestions()
				Snackbar.make(
					requireViewBinding().recyclerView,
					R.string.suggestions_updating,
					Snackbar.LENGTH_LONG,
				).show()
				true
			}

			R.id.action_settings_suggestions -> {
				router.openSuggestionsSettings()
				true
			}

			else -> false
		}
	}

	companion object {

		@Deprecated(
			"",
			ReplaceWith(
				"SuggestionsFragment()",
				"org.haziffe.dropsauce.suggestions.ui.SuggestionsFragment",
			),
		)
		fun newInstance() = SuggestionsFragment()
	}
}
