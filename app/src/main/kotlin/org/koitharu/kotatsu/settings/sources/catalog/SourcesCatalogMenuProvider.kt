package org.koitharu.kotatsu.settings.sources.catalog

import android.app.Activity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings

class SourcesCatalogMenuProvider(
	private val activity: Activity,
	private val viewModel: SourcesCatalogViewModel,
	private val expandListener: MenuItem.OnActionExpandListener,
	private val isExternalOnly: Boolean,
	private val settings: AppSettings,
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
		R.id.action_private_mode -> {
			(activity as? SourcesCatalogActivity)?.onPrivateModeToggled()
			true
		}
		else -> false
	}

	override fun onPrepareMenu(menu: Menu) {
		val isPrivate = settings.isPrivateInstallEnabled
		menu.findItem(R.id.action_private_mode).apply {
			isVisible = isExternalOnly || viewModel.content.value.isNotEmpty()
			icon = ContextCompat.getDrawable(
				activity,
				if (isPrivate) R.drawable.ic_shield else R.drawable.ic_shield_off,
			)
			title = activity.getString(
				if (isPrivate) R.string.private_extensions_enabled else R.string.private_extensions_disabled,
			)
		}
		menu.findItem(R.id.action_repo).apply {
			isVisible = isExternalOnly || viewModel.content.value.isNotEmpty()
			icon = ContextCompat.getDrawable(activity, R.drawable.ic_edit)
		}
		menu.findItem(R.id.action_repo_remove).isVisible = false
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		// Don't force-expand the appbar: the toolbar is pinned so the search field is already
		// visible when collapsed, and expanding would drag the title back down under the search bar.
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
