package org.koitharu.kotatsu.details.ui.pager

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.slider.TickVisibilityMode
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.ScanlatorMerge
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.dialog.showMultiChoiceDialog
import org.koitharu.kotatsu.core.util.LocaleStringComparator
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.ui.util.PredictiveBackCallback
import org.koitharu.kotatsu.core.util.ext.setValueRounded
import org.koitharu.kotatsu.core.util.ext.setOptionalIconsVisibleCompat
import org.koitharu.kotatsu.core.util.progress.IntPercentLabelFormatter
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_BOOKMARKS
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_CHAPTERS
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_PAGES
import java.lang.ref.WeakReference

class ChapterPagesMenuProvider(
	private val sheet: BaseAdaptiveSheet<*>,
	private val pager: ViewPager2,
	private val settings: AppSettings,
	private val viewModel: ChaptersPagesViewModel,
	private val toolbarContent: View,
) : PredictiveBackCallback(false), MenuProvider, MenuItem.OnActionExpandListener,
	SearchView.OnQueryTextListener,
	Slider.OnChangeListener {

	private var expandedItemRef: WeakReference<MenuItem>? = null

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		val tab = getCurrentTab()
		when (tab) {
			// Chapters tab: the search action + the list-option toggles share the toolbar's action pill.
			TAB_CHAPTERS -> {
				menuInflater.inflate(R.menu.opt_chapters, menu)
				// Match the rest of the app's checkable overflow menus (e.g. the home incognito toggle).
				menu.setOptionalIconsVisibleCompat(true)
				menu.findItem(R.id.action_search)?.let { item ->
					item.setOnActionExpandListener(this)
					(item.actionView as? SearchView)?.apply {
						setIconifiedByDefault(false)
						queryHint = item.title
						setOnQueryTextListener(this@ChapterPagesMenuProvider)
					}
				}
			}

			TAB_PAGES, TAB_BOOKMARKS -> {
				menuInflater.inflate(R.menu.opt_pages, menu)
				menu.findItem(R.id.action_grid_size)?.run {
					setOnActionExpandListener(this@ChapterPagesMenuProvider)
					(actionView as? Slider)?.setupPagesSizeSlider()
				}
			}
		}
	}

	override fun onPrepareMenu(menu: Menu) {
		super.onPrepareMenu(menu)
		menu.findItem(R.id.action_reversed)?.isChecked = settings.isChaptersReverse
		menu.findItem(R.id.action_grid_view)?.isChecked = settings.isChaptersGridView
		menu.findItem(R.id.action_sort_by_name)?.isChecked = viewModel.isChaptersSortedByName.value
		menu.findItem(R.id.action_downloaded)?.let { item ->
			item.isVisible = viewModel.mangaDetails.value?.local != null
			item.isChecked = viewModel.isDownloadedOnly.value
		}
		// only offer it when the source has more than one scanlator to merge (or unmerge)
		menu.findItem(R.id.action_merge_scanlators)?.isVisible =
			(viewModel.mangaDetails.value?.scanlators?.size ?: 0) > 1
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
		R.id.action_reversed -> {
			settings.isChaptersReverse = !menuItem.isChecked
			true
		}

		R.id.action_grid_view -> {
			settings.isChaptersGridView = !menuItem.isChecked
			true
		}

		R.id.action_sort_by_name -> {
			viewModel.setChaptersSortedByName(!menuItem.isChecked)
			true
		}

		R.id.action_downloaded -> {
			viewModel.isDownloadedOnly.value = !menuItem.isChecked
			true
		}

		R.id.action_merge_scanlators -> {
			showMergeScanlatorsDialog()
			true
		}

		else -> false
	}

	/**
	 * Pick which scanlators fold into one combined list; the rest keep their own tabs. Ticking all of
	 * them is "merge everything" (scanlators the source adds later are merged too).
	 */
	private fun showMergeScanlatorsDialog() {
		val context = pager.context
		val scanlators = viewModel.mangaDetails.value?.scanlators?.entries
			?.sortedWith(compareBy(LocaleStringComparator()) { it.key })
			?: return
		val merge = viewModel.scanlatorMerge.value
		showMultiChoiceDialog(
			context = context,
			icon = R.drawable.ic_merge,
			title = context.getString(R.string.merge_scanlators),
			message = context.getString(R.string.merge_scanlators_summary),
			options = scanlators.map { (name, count) ->
				context.getString(
					R.string.scanlator_with_chapters,
					name ?: context.getString(R.string.system_default),
					context.resources.getQuantityString(R.plurals.chapters, count, count),
				)
			},
			selectedIndices = scanlators.indices.filterTo(HashSet()) { index ->
				when (merge) {
					ScanlatorMerge.None -> false
					ScanlatorMerge.All -> true
					is ScanlatorMerge.Some -> scanlators[index].key in merge.branches
				}
			},
			confirmLabel = context.getString(R.string.apply),
			isValid = { it.size != 1 }, // one scanlator alone has nothing to merge with
			onConfirm = { selected ->
				viewModel.setScanlatorMerge(
					when (selected.size) {
						0 -> ScanlatorMerge.None
						scanlators.size -> ScanlatorMerge.All
						else -> ScanlatorMerge.Some(selected.mapTo(HashSet()) { scanlators[it].key })
					},
				)
			},
		)
	}

	override val backPreviewTarget: View?
		get() = sheet.view

	override fun onBackConfirmed() {
		expandedItemRef?.get()?.collapseActionView()
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		expandedItemRef = WeakReference(item)
		sheet.expandAndLock()
		isEnabled = true
		// The search field needs the whole bar, so the tabs (the toolbar's custom content) step aside
		// while it is open and the sheet rises to full screen for room.
		if (item.itemId == R.id.action_search) {
			toolbarContent.isGone = true
		}
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		expandedItemRef = null
		isEnabled = false
		sheet.unlock()
		if (item.itemId == R.id.action_search) {
			toolbarContent.isVisible = true
			(item.actionView as? SearchView)?.setQuery("", false)
			viewModel.performChapterSearch(null)
		}
		return true
	}

	override fun onQueryTextSubmit(query: String?): Boolean = false

	override fun onQueryTextChange(newText: String?): Boolean {
		viewModel.performChapterSearch(newText)
		return true
	}

	override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
		if (fromUser) {
			settings.gridSizePages = value.toInt()
		}
	}

	private fun Slider.setupPagesSizeSlider() {
		valueFrom = 50f
		valueTo = 150f
		stepSize = 5f
		tickVisibilityMode = TickVisibilityMode.TICK_VISIBILITY_HIDDEN
		labelBehavior = LabelFormatter.LABEL_FLOATING
		setLabelFormatter(IntPercentLabelFormatter(context))
		setValueRounded(settings.gridSizePages.toFloat())
		addOnChangeListener(this@ChapterPagesMenuProvider)
	}

	private fun getCurrentTab(): Int {
		var page = pager.currentItem
		if (page > 0 && pager.adapter?.itemCount == 2) { // no Pages page
			page++ // shift
		}
		return page
	}
}
