package org.haziffe.dropsauce.alternatives.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.core.exceptions.resolve.SnackbarErrorObserver
import org.haziffe.dropsauce.core.model.getTitle
import org.haziffe.dropsauce.core.nav.router
import org.haziffe.dropsauce.core.ui.BaseActivity
import org.haziffe.dropsauce.core.ui.BaseListAdapter
import org.haziffe.dropsauce.core.ui.dialog.buildAlertDialog
import org.haziffe.dropsauce.core.ui.list.OnListItemClickListener
import org.haziffe.dropsauce.core.util.ext.consumeAllSystemBarsInsets
import org.haziffe.dropsauce.core.util.ext.observe
import org.haziffe.dropsauce.core.util.ext.observeEvent
import org.haziffe.dropsauce.core.util.ext.systemBarsInsets
import org.haziffe.dropsauce.databinding.ActivityAlternativesBinding
import org.haziffe.dropsauce.list.ui.adapter.ListItemType
import org.haziffe.dropsauce.list.ui.adapter.ListStateHolderListener
import org.haziffe.dropsauce.list.ui.adapter.TypedListSpacingDecoration
import org.haziffe.dropsauce.list.ui.adapter.buttonFooterAD
import org.haziffe.dropsauce.list.ui.adapter.emptyStateListAD
import org.haziffe.dropsauce.list.ui.adapter.loadingFooterAD
import org.haziffe.dropsauce.list.ui.adapter.loadingStateAD
import org.haziffe.dropsauce.list.ui.model.ListModel
import org.haziffe.dropsauce.parsers.model.Manga
import javax.inject.Inject

@AndroidEntryPoint
class AlternativesActivity : BaseActivity<ActivityAlternativesBinding>(),
	ListStateHolderListener,
	OnListItemClickListener<MangaAlternativeModel> {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by viewModels<AlternativesViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityAlternativesBinding.inflate(layoutInflater))
		supportActionBar?.run {
			setDisplayHomeAsUpEnabled(true)
			subtitle = viewModel.manga.title
		}
		val listAdapter = BaseListAdapter<ListModel>()
			.addDelegate(ListItemType.MANGA_LIST_DETAILED, alternativeAD(coil, this, this))
			.addDelegate(ListItemType.STATE_EMPTY, emptyStateListAD(null))
			.addDelegate(ListItemType.FOOTER_LOADING, loadingFooterAD())
			.addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
			.addDelegate(ListItemType.FOOTER_BUTTON, buttonFooterAD(this))
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, addHorizontalPadding = false))
			adapter = listAdapter
		}

		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.recyclerView, null))
		viewModel.list.observe(this, listAdapter)
		viewModel.onMigrated.observeEvent(this) {
			Toast.makeText(this, R.string.migration_completed, Toast.LENGTH_SHORT).show()
			router.openDetails(it)
			finishAfterTransition()
		}
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.recyclerView.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			bottom = barsInsets.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			top = barsInsets.top,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onItemClick(item: MangaAlternativeModel, view: View) {
		when (view.id) {
			R.id.chip_source -> router.openSearch(item.manga.source, viewModel.manga.title)
			R.id.button_migrate -> confirmMigration(item.manga)
			else -> router.openDetails(item.manga)
		}
	}

	override fun onRetryClick(error: Throwable) = viewModel.retry()

	override fun onEmptyActionClick() = Unit

	override fun onFooterButtonClick() = viewModel.continueSearch()

	private fun confirmMigration(target: Manga) {
		buildAlertDialog(this, isCentered = true) {
			setIcon(R.drawable.ic_replace)
			setTitle(R.string.manga_migration)
			setMessage(
				getString(
					R.string.migrate_confirmation,
					viewModel.manga.title,
					viewModel.manga.source.getTitle(context),
					target.title,
					target.source.getTitle(context),
				),
			)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.migrate) { _, _ ->
				viewModel.migrate(target)
			}
		}.show()
	}
}
