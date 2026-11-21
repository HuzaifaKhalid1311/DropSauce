package org.koitharu.kotatsu.reader.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R

class ReaderMenuProvider(
	private val onOpenMenu: () -> Unit,
	private val onSearchBook: () -> Unit,
	private val onShowUpscalePreview: () -> Unit,
	private val isSearchable: () -> Boolean,
	private val isUpscaleActive: () -> Boolean,
	private val isLandscape: Boolean,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_reader, menu)
		menu.findItem(R.id.action_reader_menu)?.isVisible = !isLandscape
	}

	override fun onPrepareMenu(menu: Menu) {
		menu.findItem(R.id.action_epub_search)?.isVisible = isSearchable()
		menu.findItem(R.id.action_upscale_preview)?.isVisible = isUpscaleActive()
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		return when (menuItem.itemId) {
			R.id.action_epub_search -> {
				onSearchBook()
				true
			}

			R.id.action_reader_menu -> {
				onOpenMenu()
				true
			}

			R.id.action_upscale_preview -> {
				onShowUpscalePreview()
				true
			}

			R.id.action_info -> {
				// TODO
				true
			}

			else -> false
		}
	}
}
