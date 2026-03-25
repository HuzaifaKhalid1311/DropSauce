package org.koitharu.kotatsu.settings.sources.catalog

import android.app.Activity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner

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
		R.id.action_repo_remove -> {
			(activity as? SourcesCatalogActivity)?.onRemoveRepoRequested()
			true
		}
		R.id.action_filter_extensions_all -> {
			viewModel.setExtensionsSectionFilter(SourcesCatalogViewModel.ExtensionsSectionFilter.ALL)
			true
		}
		R.id.action_filter_extensions_updates -> {
			viewModel.setExtensionsSectionFilter(SourcesCatalogViewModel.ExtensionsSectionFilter.UPDATES)
			true
		}
		R.id.action_filter_extensions_installed -> {
			viewModel.setExtensionsSectionFilter(SourcesCatalogViewModel.ExtensionsSectionFilter.INSTALLED)
			true
		}
		R.id.action_filter_extensions_available -> {
			viewModel.setExtensionsSectionFilter(SourcesCatalogViewModel.ExtensionsSectionFilter.AVAILABLE)
			true
		}
		else -> false
	}

	override fun onPrepareMenu(menu: Menu) {
		val isExternalMode = isExternalOnly || viewModel.appliedFilter.value.mode == SourcesCatalogMode.MIHON
		menu.findItem(R.id.action_repo).isVisible = isExternalMode
		menu.findItem(R.id.action_filter_extensions).isVisible = isExternalMode
		menu.findItem(R.id.action_repo_remove).isVisible = isExternalMode && viewModel.hasExternalRepoConfigured()
		when (viewModel.extensionsSectionFilter.value) {
			SourcesCatalogViewModel.ExtensionsSectionFilter.ALL ->
				menu.findItem(R.id.action_filter_extensions_all).isChecked = true
			SourcesCatalogViewModel.ExtensionsSectionFilter.UPDATES ->
				menu.findItem(R.id.action_filter_extensions_updates).isChecked = true
			SourcesCatalogViewModel.ExtensionsSectionFilter.INSTALLED ->
				menu.findItem(R.id.action_filter_extensions_installed).isChecked = true
			SourcesCatalogViewModel.ExtensionsSectionFilter.AVAILABLE ->
				menu.findItem(R.id.action_filter_extensions_available).isChecked = true
		}
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		(activity as? AppBarOwner)?.appBar?.setExpanded(true, true)
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
