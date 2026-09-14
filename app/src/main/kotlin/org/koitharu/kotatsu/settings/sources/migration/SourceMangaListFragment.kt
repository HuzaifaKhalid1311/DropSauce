package org.koitharu.kotatsu.settings.sources.migration

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dagger.hilt.android.AndroidEntryPoint
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.util.RoundedTopShape
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import javax.inject.Inject

@AndroidEntryPoint
class SourceMangaListFragment : BaseComposeSettingsFragment(0) {

	@Inject
	lateinit var imageLoader: ImageLoader

	private val viewModel by viewModels<SourceMangaListViewModel>()
	private var actionMode: ActionMode? = null

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val state by viewModel.state.collectAsState()
				SourceMangaListScreen(
					state = state,
					imageLoader = imageLoader,
					onMangaClick = { manga ->
						if (state.selectedIds.isEmpty()) {
							router.openDetails(manga)
						} else {
							viewModel.toggleSelection(manga.id)
						}
					},
					onMangaLongClick = { viewModel.toggleSelection(it.id) },
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				viewModel.state
					.map { it.selectedIds }
					.distinctUntilChanged()
					.collect(::syncActionMode)
			}
		}
	}

	override fun onResume() {
		super.onResume()
		requireActivity().title = viewModel.sourceTitle
	}

	private fun syncActionMode(selectedIds: Set<Long>) {
		if (selectedIds.isEmpty()) {
			actionMode?.finish()
			return
		}
		if (actionMode == null) {
			actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(actionModeCallback)
		}
		actionMode?.title = selectedIds.size.toString()
		actionMode?.invalidate()
	}

	private val actionModeCallback = object : ActionMode.Callback {
		override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
			menu.add(Menu.NONE, ACTION_REMOVE, Menu.NONE, R.string.remove_from_favourites_and_history).apply {
				setIcon(R.drawable.ic_delete)
				setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
			}
			menu.add(Menu.NONE, ACTION_SELECT_RANGE, Menu.NONE, R.string.select_range).apply {
				setIcon(R.drawable.ic_select_range)
				setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
			}
			menu.add(Menu.NONE, ACTION_SELECT_ALL, Menu.NONE, android.R.string.selectAll).apply {
				setIcon(R.drawable.ic_select_group)
				setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
			}
			return true
		}

		override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
			val state = viewModel.state.value
			mode.title = state.selectedIds.size.toString()
			menu.findItem(ACTION_SELECT_ALL)?.isVisible = state.selectedIds.size < state.manga.size
			val selectedIndices = state.manga.mapIndexedNotNull { index, manga ->
				index.takeIf { manga.id in state.selectedIds }
			}
			menu.findItem(ACTION_SELECT_RANGE)?.isVisible =
				selectedIndices.size >= 2 &&
					selectedIndices.last() - selectedIndices.first() + 1 > selectedIndices.size
			return true
		}

		override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
			return when (item.itemId) {
				ACTION_REMOVE -> {
					MaterialAlertDialogBuilder(requireContext())
						.setTitle(R.string.remove_from_favourites_and_history)
						.setMessage(R.string.remove_from_favourites_and_history_confirm)
						.setNegativeButton(android.R.string.cancel, null)
						.setPositiveButton(R.string.remove) { _, _ ->
							viewModel.removeSelected()
							mode.finish()
						}
						.show()
					true
				}

				ACTION_SELECT_RANGE -> {
					viewModel.selectRange()
					true
				}

				ACTION_SELECT_ALL -> {
					viewModel.selectAll()
					true
				}

				else -> false
			}
		}

		override fun onDestroyActionMode(mode: ActionMode) {
			actionMode = null
			viewModel.clearSelection()
		}
	}

	companion object {
		private const val ACTION_REMOVE = 0x524D56
		private const val ACTION_SELECT_RANGE = 0x524E47
		private const val ACTION_SELECT_ALL = 0x53414C
		const val ARG_SOURCE_KEYS = "source_keys"
		const val ARG_SOURCE_TITLE = "source_title"

		fun args(sourceKeys: Collection<String>, sourceTitle: String) = Bundle(2).apply {
			putStringArray(ARG_SOURCE_KEYS, sourceKeys.toTypedArray())
			putString(ARG_SOURCE_TITLE, sourceTitle)
		}
	}
}

@Composable
private fun SourceMangaListScreen(
	state: SourceMangaListState,
	imageLoader: ImageLoader,
	onMangaClick: (Manga) -> Unit,
	onMangaLongClick: (Manga) -> Unit,
) {
	if (state.isLoading) {
		Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			LoadingIndicator()
		}
		return
	}
	LazyColumn(
		modifier = Modifier
			.fillMaxSize()
			.navigationBarsPadding()
			// cut rows under the toolbar with a row's own radius, at a row's own edges
			.clip(RoundedTopShape(radius = 12.dp, inset = 8.dp)),
	) {
		items(state.manga, key = Manga::id) { manga ->
			SourceMangaRow(
				manga = manga,
				imageLoader = imageLoader,
				isSelected = manga.id in state.selectedIds,
				onClick = { onMangaClick(manga) },
				onLongClick = { onMangaLongClick(manga) },
			)
			HorizontalDivider(
				modifier = Modifier.padding(start = 92.dp, end = 16.dp),
				color = MaterialTheme.colorScheme.outlineVariant,
			)
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceMangaRow(
	manga: Manga,
	imageLoader: ImageLoader,
	isSelected: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
) {
	val context = LocalContext.current
	val coverRequest = remember(manga.id, manga.coverUrl, manga.source) {
		manga.coverUrl?.takeIf { it.isNotBlank() }?.let { coverUrl ->
			ImageRequest.Builder(context)
				.data(coverUrl)
				.mangaSourceExtra(manga.source)
				.build()
		}
	}
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 8.dp, vertical = 2.dp)
			.clip(RoundedCornerShape(12.dp))
			.heightIn(min = 84.dp)
			.background(
				if (isSelected) {
					MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
				} else {
					Color.Transparent
				},
			)
			.then(
				if (isSelected) {
					Modifier.border(
						width = 1.dp,
						color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
						shape = RoundedCornerShape(12.dp),
					)
				} else {
					Modifier
				},
			)
			.combinedClickable(onClick = onClick, onLongClick = onLongClick)
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			modifier = Modifier
				.width(60.dp)
				.aspectRatio(0.72f)
				.clip(RoundedCornerShape(8.dp))
				.background(MaterialTheme.colorScheme.surfaceContainerHighest),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				painter = painterResource(R.drawable.ic_placeholder),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
				modifier = Modifier.size(36.dp),
			)
			if (coverRequest != null) {
				AsyncImage(
					model = coverRequest,
					imageLoader = imageLoader,
					contentDescription = null,
					contentScale = ContentScale.Crop,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}
		Spacer(Modifier.width(16.dp))
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = manga.title,
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 2,
			)
			manga.authors.firstOrNull()?.let { author ->
				Text(
					text = author,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
				)
			}
		}
	}
}
