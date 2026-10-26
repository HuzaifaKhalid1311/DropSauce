package org.koitharu.kotatsu.search.ui

import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.getSummary
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.model.parcelable.ParcelableMangaListFilter
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.model.titleRes
import org.koitharu.kotatsu.core.util.ViewBadge
import org.koitharu.kotatsu.core.util.ext.bindExpandedSearchTitle
import org.koitharu.kotatsu.core.util.ext.buildBundle
import org.koitharu.kotatsu.core.util.ext.consumeSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.end
import org.koitharu.kotatsu.core.util.ext.getParcelableExtraCompat
import org.koitharu.kotatsu.core.util.ext.getSerializableExtraCompat
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.core.util.ext.start
import org.koitharu.kotatsu.databinding.ActivityMangaListBinding
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.filter.ui.FilterCoordinator
import org.koitharu.kotatsu.filter.ui.FilterHeaderFragment
import org.koitharu.kotatsu.mihon.MihonFilterMapper
import org.koitharu.kotatsu.list.ui.preview.PreviewFragment
import org.koitharu.kotatsu.local.ui.LocalListFragment
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.remotelist.ui.RemoteListFragment
import kotlin.math.roundToInt

private const val SORT_BUTTON_MAX_WIDTH_FRACTION = 0.45f

@AndroidEntryPoint
class MangaListActivity :
	BaseActivity<ActivityMangaListBinding>(),
	AppBarOwner, View.OnClickListener,
	FilterCoordinator.Owner {

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	override val filterCoordinator: FilterCoordinator
		get() = checkNotNull(findFilterOwner()) {
			"Cannot find FilterCoordinator.Owner fragment in ${supportFragmentManager.fragments}"
		}.filterCoordinator

	@javax.inject.Inject
	lateinit var sourcesRepository: MangaSourcesRepository

	private lateinit var source: MangaSource

	// The active language's native name, shown as the top bar's subheading.
	private var activeLanguageName: String? = null

	// Original (attr-defined) expanded height and title bottom margin of the collapsing toolbar,
	// captured before growing them to make room for the language button on its own line.
	private var expandedBaseHeight = -1
	private var titleBaseMarginBottom = 0

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityMangaListBinding.inflate(layoutInflater))
		val filter = intent.getParcelableExtraCompat<ParcelableMangaListFilter>(AppRouter.KEY_FILTER)?.filter
		val sortOrder = intent.getSerializableExtraCompat<SortOrder>(AppRouter.KEY_SORT_ORDER)
		// Collapse multi-language sources to their active variant so the screen always reflects the
		// language chosen in source settings, even if it was opened via a different variant.
		val resolved = sourcesRepository.resolveActiveSource(MangaSource(intent.getStringExtra(AppRouter.KEY_SOURCE)))
		source = resolved.source
		activeLanguageName = resolved.languageSubtitle
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		viewBinding.buttonOrder?.setOnClickListener(this)
		viewBinding.buttonLanguage?.setOnClickListener {
			router.openSourceSettings(source, scrollToLanguage = true)
		}
		configureSortButton()
		applyTitle()
		initList(source, filter, sortOrder)
	}

	override fun onResume() {
		super.onResume()
		// If the active language was changed in source settings, reload the list in place for the new
		// variant so the user doesn't have to back out to Explore and reopen the extension.
		val resolved = sourcesRepository.resolveActiveSource(source)
		if (resolved.source.name != source.name) {
			source = resolved.source
			activeLanguageName = resolved.languageSubtitle
			applyTitle()
			reloadList(source)
		}
	}

	/**
	 * Shows the source name as the title with the active language as a text button (styled like the
	 * sort button): inline after the expanded title when it fits without reaching the sort button,
	 * otherwise on its own line below the name. Spans inside a CollapsingToolbarLayout title render
	 * as overlapping ghost text during the collapse cross-fade, so the language must not be inlined
	 * into the title itself. In both cases only the name collapses into the top bar.
	 */
	private fun applyTitle() {
		val name = source.getTitle(this)
		val lang = activeLanguageName
		title = name
		val ctl = viewBinding.collapsingToolbarLayout ?: return
		ctl.title = name
		if (expandedBaseHeight < 0) {
			expandedBaseHeight = ctl.layoutParams.height
			titleBaseMarginBottom = ctl.expandedTitleMarginBottom
		}
		if (lang.isNullOrEmpty()) {
			viewBinding.buttonLanguage?.isVisible = false
			applyExpandedHeight(ctl, extra = 0)
		} else {
			viewBinding.root.doOnLayout { applyLanguage(ctl, name, lang) }
		}
	}

	private fun applyLanguage(ctl: CollapsingToolbarLayout, name: String, lang: String) {
		val button = viewBinding.buttonLanguage ?: return
		button.text = lang
		button.isVisible = true
		// Same style as the sort button, so the same nudge puts its label on the title's baseline.
		button.translationY = viewBinding.buttonOrder?.translationY ?: 0f
		val row = button.parent as View
		val titlePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
			typeface = ctl.expandedTitleTypeface
			textSize = ctl.expandedTitleTextSize
		}
		val titleEnd = ctl.expandedTitleMarginStart + titlePaint.measureText(name) +
			resources.displayMetrics.density * 12f
		// Offset by the button's own padding so its label (not its touch area) lines up with the text.
		val labelWidth = button.paint.measureText(lang)
		if (titleEnd + labelWidth <= ctl.width - ctl.expandedTitleMarginEnd) {
			button.updateLayoutParams<ViewGroup.MarginLayoutParams> {
				marginStart = (titleEnd - row.paddingStart - button.paddingStart).roundToInt()
			}
			applyExpandedHeight(ctl, extra = 0)
		} else {
			// No room after the name: put the button on its own line below it, lifting the title by
			// one line (and growing the expanded area so the title doesn't run into the nav icon).
			button.updateLayoutParams<ViewGroup.MarginLayoutParams> {
				marginStart = ctl.expandedTitleMarginStart - row.paddingStart - button.paddingStart
			}
			applyExpandedHeight(ctl, extra = (resources.displayMetrics.density * 24f).roundToInt())
		}
	}

	private fun applyExpandedHeight(ctl: CollapsingToolbarLayout, extra: Int) {
		ctl.expandedTitleMarginBottom = titleBaseMarginBottom + extra
		ctl.updateLayoutParams { height = expandedBaseHeight + extra }
		(viewBinding.buttonOrder?.parent as? View)?.updateLayoutParams { height = expandedBaseHeight + extra }
	}

	/**
	 * While a search action view is expanded, Toolbar hides the CollapsingToolbarLayout's internal
	 * title anchor, so the CTL cannot draw the title at all — even in the expanded position below
	 * the toolbar. Swap in a real TextView at the expanded title's exact position for the duration
	 * of the search; it lives in the parallax layer, so it collapses away like the title would.
	 */
	fun setSearchExpanded(expanded: Boolean) {
		val ctl = viewBinding.collapsingToolbarLayout ?: return
		viewBinding.textTitleSearch?.bindExpandedSearchTitle(ctl, ctl.title, expanded)
	}

	private fun configureSortButton() {
		val button = viewBinding.buttonOrder ?: return
		button.maxLines = 1
		button.ellipsize = TextUtils.TruncateAt.END
		button.doOnLayout {
			val maxWidth = (viewBinding.root.width * SORT_BUTTON_MAX_WIDTH_FRACTION).roundToInt()
			button.maxWidth = maxWidth
			val ctl = viewBinding.collapsingToolbarLayout ?: return@doOnLayout
			ctl.expandedTitleMarginEnd = maxWidth +
				resources.getDimensionPixelOffset(R.dimen.toolbar_button_margin) * 2
			// The button is bottom-gravity in the parallax row, but its own vertical padding and
			// insets leave its label sitting above the expanded title's baseline. Nudge it down so
			// both baselines land on the same line (applyLanguage reuses it for the language button).
			button.translationY = (button.height - button.baseline - titleBaseMarginBottom)
				.toFloat().coerceAtLeast(0f)
			viewBinding.buttonLanguage?.translationY = button.translationY
		}
	}

	/**
	 * Replaces the list (and filter header/side) with a fresh fragment for [newSource]. A plain
	 * recreate() can't be used here: the FragmentManager would restore the previous source's list
	 * fragment, so only the title would change while the content stayed on the old language.
	 */
	private fun reloadList(newSource: MangaSource) {
		supportFragmentManager.commit {
			setReorderingAllowed(true)
			replace(R.id.container, RemoteListFragment.newInstance(newSource))
			// The filter header/side capture their FilterCoordinator at creation, so recreate them
			// too — otherwise they keep driving the old source's list.
			if (viewBinding.containerFilterHeader != null) {
				replace(R.id.container_filter_header, FilterHeaderFragment::class.java, null)
			}
			runOnCommit { findFilterOwner()?.let { initFilter(it) } }
		}
	}

	override fun isNsfwContent(): Flow<Boolean> =
		flowOf(MangaSource(intent.getStringExtra(AppRouter.KEY_SOURCE)).isNsfw())

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.cardSide?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			marginEnd = barsInsets.end(v) + resources.getDimensionPixelOffset(R.dimen.side_card_offset)
			topMargin = barsInsets.top + resources.getDimensionPixelOffset(R.dimen.grid_spacing_outer_double)
			bottomMargin = barsInsets.bottom + resources.getDimensionPixelOffset(R.dimen.side_card_offset)
		}
		// The collapsing appbar is pinned (exitUntilCollapsed), so it owns the status bar area via
		// top padding, same as the Downloads screen. The landscape layout has no collapsing bar.
		// The side card only appears while previewing; whenever it's away the list spans the full width,
		// so the top bar has to carry the end inset itself.
		viewBinding.appbar.updatePaddingRelative(
			end = if (viewBinding.cardSide?.isVisible != true) barsInsets.end(v) else 0,
			start = barsInsets.start(v),
			top = if (viewBinding.collapsingToolbarLayout != null) barsInsets.top else viewBinding.appbar.paddingTop,
		)
		return insets.consumeSystemBarsInsets(v, end = true)
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_order -> router.showSortSheet()
		}
	}

	fun showPreview(manga: Manga): Boolean = setSideFragment(
		PreviewFragment::class.java,
		buildBundle(1) {
			putParcelable(AppRouter.KEY_MANGA, ParcelableManga(manga))
		},
	)

	/**
	 * Empties the side card and hides it, giving the list the full width back. The card is only for
	 * previews now — the filter opens as a sheet from the top bar, so nothing stays docked there.
	 */
	fun hidePreview() {
		val card = viewBinding.cardSide ?: return
		supportFragmentManager.findFragmentById(R.id.container_side)?.let { fragment ->
			supportFragmentManager.commit {
				setReorderingAllowed(true)
				remove(fragment)
			}
		}
		card.isVisible = false
		// The top bar's end inset depends on whether the card is showing.
		ViewCompat.requestApplyInsets(viewBinding.root)
	}

	private fun initList(source: MangaSource, filter: MangaListFilter?, sortOrder: SortOrder?) {
		val fm = supportFragmentManager
		val existingFragment = fm.findFragmentById(R.id.container)
		if (existingFragment is FilterCoordinator.Owner) {
			initFilter(existingFragment)
		} else {
			fm.commit {
				setReorderingAllowed(true)
				val fragment = if (source == LocalMangaSource) {
					LocalListFragment()
				} else {
					RemoteListFragment.newInstance(source)
				}
				if (filter != null || sortOrder != null) {
					fragment.arguments = (fragment.arguments ?: Bundle()).apply {
						putBoolean(RemoteListFragment.ARG_PRESET_FILTER, true)
					}
				}
				replace(R.id.container, fragment)
				runOnCommit { initFilter(fragment) }
				if (filter != null || sortOrder != null) {
					runOnCommit(ApplyFilterRunnable(fragment, filter, sortOrder))
				}
			}
		}
	}

	private fun initFilter(filterOwner: FilterCoordinator.Owner) {
		if (viewBinding.containerFilterHeader != null) {
			if (supportFragmentManager.findFragmentById(R.id.container_filter_header) == null) {
				supportFragmentManager.commit {
					setReorderingAllowed(true)
					replace(R.id.container_filter_header, FilterHeaderFragment::class.java, null)
				}
			}
		}
		val filter = filterOwner.filterCoordinator
		val chipSort = viewBinding.buttonOrder
		if (chipSort != null) {
			val filterBadge = ViewBadge(chipSort, this)
			filterBadge.setMaxCharacterCount(0)
			val isDynamic = filter.isDynamicFilter
			filter.observe().observe(this) { snapshot ->
				if (isDynamic) {
					// A server-side sort lives inside the Mihon FilterList (encoded as a "srt@" tag) and
					// wins over the in-app Popular/Latest listing; only non-sort tags count towards the
					// "filter applied" badge. The icon says which of the two kinds is active.
					val sortTag = snapshot.listFilter.tags.firstOrNull { it.key.startsWith(MihonFilterMapper.SORT_KEY_PREFIX) }
					if (sortTag != null) {
						chipSort.text = sortTag.title.substringAfter(": ")
						chipSort.setIconResource(R.drawable.ic_sort)
					} else if (snapshot.sortOrder == SortOrder.UPDATED) {
						chipSort.setText(R.string.latest)
						chipSort.setIconResource(R.drawable.ic_sort_latest)
					} else {
						chipSort.setText(R.string.popular)
						chipSort.setIconResource(R.drawable.ic_sort_popular)
					}
					chipSort.isVisible = true
					filterBadge.counter = if (snapshot.listFilter.tags.any { !it.key.startsWith(MihonFilterMapper.SORT_KEY_PREFIX) }) 1 else 0
				} else {
					chipSort.setTextAndVisible(snapshot.sortOrder.titleRes)
					filterBadge.counter = if (snapshot.listFilter.hasNonSearchOptions()) 1 else 0
				}
			}
		} else {
			filter.observe().map {
				it.listFilter.getSummary()
			}.flowOn(Dispatchers.Default)
				.observe(this) {
					supportActionBar?.subtitle = it
				}
		}
	}

	private fun findFilterOwner(): FilterCoordinator.Owner? {
		return supportFragmentManager.findFragmentById(R.id.container) as? FilterCoordinator.Owner
	}

	private fun setSideFragment(cls: Class<out Fragment>, args: Bundle?) = if (viewBinding.containerSide != null) {
		supportFragmentManager.commit {
			setReorderingAllowed(true)
			replace(R.id.container_side, cls, args)
		}
		viewBinding.cardSide?.isVisible = true
		ViewCompat.requestApplyInsets(viewBinding.root)
		true
	} else {
		false
	}

	private class ApplyFilterRunnable(
		private val filterOwner: FilterCoordinator.Owner,
		private val filter: MangaListFilter?,
		private val sortOrder: SortOrder?,
	) : Runnable {

		override fun run() {
			if (sortOrder != null) {
				filterOwner.filterCoordinator.setSortOrder(sortOrder)
			}
			if (filter != null) {
				filterOwner.filterCoordinator.setAdjusted(filter)
			}
		}
	}
}
