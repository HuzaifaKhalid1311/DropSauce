package org.koitharu.kotatsu.settings.sources.catalog

import android.app.Activity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R

class SourcesCatalogMenuProvider(
	private val activity: Activity,
	private val viewModel: SourcesCatalogViewModel,
	private val expandListener: MenuItem.OnActionExpandListener,
	private val isExternalOnly: Boolean,
) : MenuProvider,
	MenuItem.OnActionExpandListener,
	SearchView.OnQueryTextListener {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_sources_catalog, menu)
		val searchMenuItem = menu.findItem(R.id.action_search)
		searchMenuItem.setOnActionExpandListener(this)
		val searchView = searchMenuItem.actionView as SearchView
		searchView.setOnQueryTextListener(this)
		searchView.setIconifiedByDefault(false)
		searchView.queryHint = searchMenuItem.title
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
		R.id.action_repo -> {
			(activity as? SourcesCatalogActivity)?.onManageRepoRequested()
			true
		}
		R.id.action_extension_settings -> {
			(activity as? SourcesCatalogActivity)?.onExtensionSettingsRequested()
			true
		}
		R.id.action_update_all -> {
			(activity as? SourcesCatalogActivity)?.onUpdateAllRequested()
			true
		}
		else -> false
	}

	override fun onPrepareMenu(menu: Menu) {
		menu.findItem(R.id.action_extension_settings).apply {
			isVisible = isExternalOnly || viewModel.content.value.items.isNotEmpty()
			icon = ContextCompat.getDrawable(activity, R.drawable.ic_settings)
		}
		menu.findItem(R.id.action_repo).apply {
			isVisible = isExternalOnly || viewModel.content.value.items.isNotEmpty()
			icon = ContextCompat.getDrawable(activity, R.drawable.ic_edit)
		}
		menu.findItem(R.id.action_update_all).isVisible =
			(activity as? SourcesCatalogActivity)?.isUpdatesPageSelected() == true && viewModel.hasUpdates.value
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		return expandListener.onMenuItemActionExpand(item)
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		(item.actionView as SearchView).setQuery("", false)
		return expandListener.onMenuItemActionCollapse(item)
	}

	override fun onQueryTextSubmit(query: String?): Boolean = false

	override fun onQueryTextChange(newText: String?): Boolean {
		viewModel.performSearch(newText?.trim().orEmpty())
		return true
	}
}
