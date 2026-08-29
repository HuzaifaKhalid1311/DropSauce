package org.koitharu.kotatsu.search.ui.multi

import android.app.Activity
import android.os.Build
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.search.domain.SearchKind

class SearchMenuProvider(
	private val activity: SearchActivity,
	private val viewModel: SearchViewModel,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_search_kind, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		super.onPrepareMenu(menu)
		menu.findItem(
			when (viewModel.kind) {
				SearchKind.SIMPLE -> R.id.action_kind_simple
				SearchKind.TITLE -> R.id.action_kind_title
				SearchKind.AUTHOR -> R.id.action_kind_author
				SearchKind.TAG -> R.id.action_kind_tag
			},
		)?.isChecked = true
		menu.findItem(R.id.action_filter_pinned_only)?.run {
			isChecked = viewModel.isPinnedOnly
			// Pinned sources are not searched at all in local-only mode.
			isEnabled = !viewModel.isLocalOnly
		}
		menu.findItem(R.id.action_filter_local_only)?.isChecked = viewModel.isLocalOnly
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		when (menuItem.itemId) {
			R.id.action_filter_pinned_only -> {
				menuItem.isChecked = !menuItem.isChecked
				viewModel.setPinnedOnly(menuItem.isChecked)
				return true
			}

			R.id.action_filter_local_only -> {
				menuItem.isChecked = !menuItem.isChecked
				viewModel.setLocalOnly(menuItem.isChecked)
				// onPrepareMenu only runs when the menu is rebuilt, so without this the pinned item
				// keeps whatever enabled state it had when the menu was last built.
				activity.invalidateMenu()
				return true
			}
		}

		val newKind = when (menuItem.itemId) {
			R.id.action_kind_simple -> SearchKind.SIMPLE
			R.id.action_kind_title -> SearchKind.TITLE
			R.id.action_kind_author -> SearchKind.AUTHOR
			R.id.action_kind_tag -> SearchKind.TAG
			else -> return false
		}
		if (newKind != viewModel.kind) {
			activity.router.openSearch(
				query = viewModel.query,
				kind = newKind,
			)
			activity.overrideSearchKindTransition()
			activity.finishAfterTransition()
		}
		return true
	}

	private fun SearchActivity.overrideSearchKindTransition() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			overrideActivityTransition(
				Activity.OVERRIDE_TRANSITION_OPEN,
				R.anim.m3_fade_through_enter,
				R.anim.m3_fade_through_exit,
			)
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			@Suppress("DEPRECATION")
			overridePendingTransition(
				R.anim.m3_fade_through_enter,
				R.anim.m3_fade_through_exit,
				0,
			)
		} else {
			@Suppress("DEPRECATION")
			overridePendingTransition(
				R.anim.m3_fade_through_enter,
				R.anim.m3_fade_through_exit,
			)
		}
	}
}
