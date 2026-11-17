package org.koitharu.kotatsu.settings.sources.migration

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.core.view.MenuProvider
import androidx.core.view.doOnDetach
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import com.google.android.material.appbar.AppBarLayout
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import dagger.hilt.android.AndroidEntryPoint
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.ui.AutoFixService
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaHandler.Companion.suppressCaptchaErrors
import org.koitharu.kotatsu.core.ui.image.FaviconDrawable
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.main.ui.nav.DrawablePainter
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import androidx.activity.addCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.settings.compose.SettingsSwitch
import org.koitharu.kotatsu.settings.reader.DragHandle
import org.koitharu.kotatsu.settings.reader.rememberReorderSlide
import kotlin.math.roundToInt
import javax.inject.Inject

@AndroidEntryPoint
class BrokenSourcesMigrationFragment :
	BaseComposeSettingsFragment(R.string.migrate_extensions) {

	private val viewModel by viewModels<BrokenSourcesMigrationViewModel>()

	@Inject
	lateinit var imageLoader: ImageLoader

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val state by viewModel.state.collectAsState()
				val progress by AutoFixService.progress.collectAsStateWithLifecycle()
				BrokenSourcesMigrationScreen(
					state = state,
					progress = progress,
					imageLoader = imageLoader,
					onToggle = viewModel::toggle,
					onToggleRecommended = {
						viewModel.toggleAll(state.sources.filter { it.isUnavailable }.map { it.key })
					},
					onOpenSource = { source ->
						(requireActivity() as SettingsActivity).openFragment(
							SourceMangaListFragment::class.java,
							SourceMangaListFragment.args(source.sourceKeys, source.title),
							isFromRoot = false,
						)
					},
					onToggleTarget = viewModel::toggleTarget,
					onToggleAllTargets = viewModel::toggleAllTargets,
					onMoveTarget = viewModel::moveTarget,
					onManualReviewChange = viewModel::setManualReview,
					onPickReplacement = ::showReplacementPicker,
					onCancel = AutoFixService::cancel,
					onConfirm = ::onConfirm,
				)
			}
		}
	}

	private fun onConfirm() {
		val state = viewModel.state.value
		when (state.step) {
			MigrationStep.SOURCES -> viewModel.openTargets()
			MigrationStep.TARGETS -> if (state.isManualReview) {
				viewModel.startReview()
			} else if (AutoFixService.startForSources(
					requireContext(),
					viewModel.selectedSourceKeys(),
					viewModel.checkedTargets(),
				)
			) {
				Toast.makeText(requireContext(), R.string.broken_sources_migration_started, Toast.LENGTH_LONG).show()
				viewModel.clearSelection()
			} else {
				Toast.makeText(requireContext(), R.string.error_occurred, Toast.LENGTH_SHORT).show()
			}

			MigrationStep.REVIEW -> viewModel.migrateReviewed()
		}
	}

	private fun showReplacementPicker(item: ReviewItem) {
		if (item.candidates.isNullOrEmpty() || childFragmentManager.findFragmentByTag(MigrationMatchSheet.TAG) != null) return
		MigrationMatchSheet.newInstance(item.seed.id).show(childFragmentManager, MigrationMatchSheet.TAG)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		// The fragment container is laid out for the collapsed appbar (ScrollingViewBehavior), so
		// while the appbar is expanded the bottom of this view is pushed off-screen. Compensate
		// with bottom padding equal to the appbar's current expansion so the Fix bar stays visible.
		val appBar = (requireActivity() as AppBarOwner).appBar
		val offsetListener = AppBarLayout.OnOffsetChangedListener { bar, verticalOffset ->
			view.updatePadding(bottom = bar.totalScrollRange + verticalOffset)
		}
		appBar.addOnOffsetChangedListener(offsetListener)
		view.doOnDetach { appBar.removeOnOffsetChangedListener(offsetListener) }
		// Back walks the steps before it leaves the screen.
		val backCallback = requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
			viewModel.back()
		}
		viewLifecycleOwner.lifecycleScope.launch {
			viewModel.state.collect { backCallback.isEnabled = it.step != MigrationStep.SOURCES }
		}
		viewModel.onReviewMigrated.observeEvent(viewLifecycleOwner) { count ->
			Toast.makeText(
				requireContext(),
				resources.getQuantityString(R.plurals.manga_migrated, count, count),
				Toast.LENGTH_LONG,
			).show()
		}
		addMenuProvider(object : MenuProvider {
			override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
				menu.add(Menu.NONE, MENU_INFO, Menu.NONE, R.string.migration_information).apply {
					setIcon(R.drawable.ic_info)
					setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
				}
			}

			override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
				if (menuItem.itemId != MENU_INFO) return false
				viewModel.toggleInfo()
				return true
			}
		})
	}

	private companion object {
		const val MENU_INFO = 0x4D4947
	}
}

@Composable
private fun BrokenSourcesMigrationScreen(
	state: BrokenSourcesMigrationState,
	progress: AutoFixService.Progress?,
	imageLoader: ImageLoader,
	onToggle: (String) -> Unit,
	onToggleRecommended: () -> Unit,
	onOpenSource: (LibrarySourceOption) -> Unit,
	onToggleTarget: (String) -> Unit,
	onToggleAllTargets: () -> Unit,
	onMoveTarget: (Int, Int) -> Unit,
	onManualReviewChange: (Boolean) -> Unit,
	onPickReplacement: (ReviewItem) -> Unit,
	onCancel: () -> Unit,
	onConfirm: () -> Unit,
) {
	// A background migration locks the setup steps; a review in progress is this screen's own work.
	val isBusy = progress != null && state.step != MigrationStep.REVIEW
	val unavailableSources = state.sources.filter(LibrarySourceOption::isUnavailable)
	val mihonSources = state.sources.filterNot(LibrarySourceOption::isUnavailable)

	Column(
		modifier = Modifier.fillMaxSize(),
	) {
		AnimatedVisibility(visible = state.isInfoVisible) {
			Column {
				Column(
					modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
				) {
					Text(
						text = stringResource(R.string.migrate_extensions_description),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
			}
		}

		if (progress != null && state.step != MigrationStep.REVIEW) {
			MigrationProgressBanner(progress)
		}

		Box(
			modifier = Modifier
				.fillMaxWidth()
				.weight(1f)
				.alpha(if (isBusy) 0.38f else 1f),
		) {
			when {
				state.step == MigrationStep.TARGETS -> TargetList(
					state = state,
					imageLoader = imageLoader,
					onToggle = onToggleTarget,
					onToggleAll = onToggleAllTargets,
					onMove = onMoveTarget,
					onManualReviewChange = onManualReviewChange,
				)

				state.step == MigrationStep.REVIEW -> ReviewList(
					state = state,
					imageLoader = imageLoader,
					onPick = onPickReplacement,
				)

				state.isLoading -> Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.Center,
				) {
					LoadingIndicator()
				}

				state.sources.isEmpty() -> Box(
					modifier = Modifier.fillMaxSize().padding(24.dp),
					contentAlignment = Alignment.Center,
				) {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						Text(
							text = stringResource(R.string.no_sources_to_migrate),
							style = MaterialTheme.typography.titleMedium,
						)
						Spacer(Modifier.height(8.dp))
						Text(
							text = stringResource(R.string.no_sources_to_migrate_summary),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}

				else -> LazyColumn(
					modifier = Modifier
						.fillMaxSize()
						.nestedScroll(rememberNestedScrollInteropConnection()),
				) {
					if (unavailableSources.isNotEmpty()) {
						item {
							SourceSectionHeader(
								title = stringResource(R.string.recommended),
								isChecked = unavailableSources.all { it.key in state.selectedSources },
								onToggle = onToggleRecommended,
								topPadding = 8.dp,
							)
						}
						items(unavailableSources, key = LibrarySourceOption::key) { source ->
							SourceCheckboxRow(
								source = source,
								imageLoader = imageLoader,
								isChecked = source.key in state.selectedSources,
								onToggle = { onToggle(source.key) },
								onOpenManga = { onOpenSource(source) },
							)
							SourceDivider()
						}
					}
					if (mihonSources.isNotEmpty()) {
						item {
							SourceSectionHeader(
								title = stringResource(R.string.mihon_sources),
								topPadding = 28.dp,
							)
						}
						items(mihonSources, key = LibrarySourceOption::key) { source ->
							SourceCheckboxRow(
								source = source,
								imageLoader = imageLoader,
								isChecked = source.key in state.selectedSources,
								onToggle = { onToggle(source.key) },
								onOpenManga = { onOpenSource(source) },
							)
							SourceDivider()
						}
					}
					item { Spacer(Modifier.height(12.dp)) }
				}
			}
			if (isBusy) {
				// Swallows every tap so nothing can be re-selected mid-migration.
				Box(
					modifier = Modifier
						.matchParentSize()
						.clickable(
							interactionSource = remember { MutableInteractionSource() },
							indication = null,
							onClick = {},
						),
				)
			}
		}

		val chosenCount = state.review.count { it.choice != null }
		Surface(
			tonalElevation = 3.dp,
			shadowElevation = 6.dp,
			color = MaterialTheme.colorScheme.surfaceContainer,
		) {
			Column(modifier = Modifier.navigationBarsPadding()) {
				HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
				Button(
					onClick = if (isBusy) onCancel else onConfirm,
					enabled = isBusy || when (state.step) {
						MigrationStep.SOURCES -> state.selectedSources.isNotEmpty()
						MigrationStep.TARGETS -> state.checkedTargets.isNotEmpty()
						MigrationStep.REVIEW -> !state.isReviewSearching && !state.isMigrating && chosenCount > 0
					},
					colors = if (isBusy) {
						ButtonDefaults.buttonColors(
							containerColor = MaterialTheme.colorScheme.error,
							contentColor = MaterialTheme.colorScheme.onError,
						)
					} else {
						ButtonDefaults.buttonColors()
					},
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp, vertical = 12.dp)
						.height(52.dp),
				) {
					Text(
						when {
							isBusy -> stringResource(android.R.string.cancel)
							state.step == MigrationStep.SOURCES -> stringResource(R.string.next)
							state.step == MigrationStep.TARGETS && state.isManualReview -> stringResource(R.string.search)
							state.step == MigrationStep.TARGETS -> stringResource(R.string.migrate)
							else -> pluralStringResource(R.plurals.migrate_manga, chosenCount, chosenCount)
						},
					)
				}
			}
		}
	}
}

/** Step 2: where to search, in priority order. Rows are uniform, so dragging works like the reader bar list. */
@Composable
private fun TargetList(
	state: BrokenSourcesMigrationState,
	imageLoader: ImageLoader,
	onToggle: (String) -> Unit,
	onToggleAll: () -> Unit,
	onMove: (Int, Int) -> Unit,
	onManualReviewChange: (Boolean) -> Unit,
) {
	val haptic = rememberHapticEffect()
	var rowPitch by remember { mutableFloatStateOf(0f) }
	var dragIndex by remember { mutableIntStateOf(-1) }
	var dragOffset by remember { mutableFloatStateOf(0f) }
	val targets = state.targets
	val onDrag: (Float) -> Unit = { dy ->
		dragOffset += dy
		if (rowPitch > 0f && dragIndex >= 0) {
			val target = (dragIndex + (dragOffset / rowPitch).roundToInt()).coerceIn(0, targets.lastIndex)
			if (target != dragIndex) {
				onMove(dragIndex, target)
				dragOffset -= (target - dragIndex) * rowPitch
				dragIndex = target
				haptic(HapticEffect.LIGHT_TICK)
			}
		}
	}
	Column(
		modifier = Modifier
			.fillMaxSize()
			.nestedScroll(rememberNestedScrollInteropConnection())
			.verticalScroll(rememberScrollState()),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clickable { onManualReviewChange(!state.isManualReview) }
				.padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.review_before_migrating),
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Text(
					text = stringResource(R.string.review_before_migrating_summary),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Spacer(Modifier.width(12.dp))
			SettingsSwitch(checked = state.isManualReview, onCheckedChange = onManualReviewChange)
		}
		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
		SourceSectionHeader(
			title = stringResource(R.string.migrate_search_in),
			isChecked = state.checkedTargets.size == targets.size,
			onToggle = onToggleAll,
			topPadding = 16.dp,
		)
		targets.forEachIndexed { index, target ->
			key(target.name) {
				val isDragging = index == dragIndex
				val slide = rememberReorderSlide(index, rowPitch, isDragging)
				Column(
					modifier = Modifier
						.zIndex(if (isDragging) 1f else 0f)
						.graphicsLayer {
							translationY = if (isDragging) dragOffset else slide.floatValue
							shadowElevation = if (isDragging) 8.dp.toPx() else 0f
						}
						.background(MaterialTheme.colorScheme.surface)
						.onSizeChanged { rowPitch = it.height.toFloat() },
				) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.heightIn(min = 64.dp)
							.clickable { onToggle(target.name) }
							.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						SourceIcon(title = target.title, source = target.source, iconUrl = null, imageLoader = imageLoader)
						Spacer(Modifier.width(12.dp))
						Text(
							text = target.title,
							style = MaterialTheme.typography.bodyLarge,
							color = MaterialTheme.colorScheme.onSurface,
							modifier = Modifier.weight(1f),
						)
						Checkbox(
							checked = target.name in state.checkedTargets,
							onCheckedChange = { onToggle(target.name) },
						)
						DragHandle(
							onDragStart = {
								dragIndex = index
								dragOffset = 0f
								haptic(HapticEffect.GESTURE_START)
							},
							onDrag = onDrag,
							onDragEnd = {
								dragIndex = -1
								dragOffset = 0f
								haptic(HapticEffect.GESTURE_END)
							},
						)
					}
					SourceDivider()
				}
			}
		}
		Spacer(Modifier.height(12.dp))
	}
}

/** Step 3 (manual): every entry with the match picked for it; tap one to change or skip it. */
@Composable
private fun ReviewList(
	state: BrokenSourcesMigrationState,
	imageLoader: ImageLoader,
	onPick: (ReviewItem) -> Unit,
) {
	Column(modifier = Modifier.fillMaxSize()) {
		if (state.isReviewSearching) {
			val done = state.review.count { it.candidates != null }
			Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
				Text(
					text = stringResource(R.string.migration_searching, done, state.reviewTotal),
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Spacer(Modifier.height(10.dp))
				if (state.reviewTotal > 0) {
					LinearProgressIndicator(
						progress = { done.toFloat() / state.reviewTotal },
						modifier = Modifier.fillMaxWidth(),
					)
				} else {
					LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
				}
			}
			HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
		}
		LazyColumn(
			modifier = Modifier
				.fillMaxSize()
				.nestedScroll(rememberNestedScrollInteropConnection()),
		) {
			items(state.review, key = { it.seed.id }) { item ->
				ReviewRow(item = item, imageLoader = imageLoader, onClick = { onPick(item) })
				SourceDivider()
			}
			item { Spacer(Modifier.height(12.dp)) }
		}
	}
}

@Composable
private fun ReviewRow(
	item: ReviewItem,
	imageLoader: ImageLoader,
	onClick: () -> Unit,
) {
	val context = LocalContext.current
	val shown = item.choice ?: item.seed
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = 72.dp)
			.clickable(enabled = !item.candidates.isNullOrEmpty(), onClick = onClick)
			.padding(horizontal = 16.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		AsyncImage(
			model = remember(shown.coverUrl, shown.source) {
				ImageRequest.Builder(context)
					.data(shown.coverUrl)
					.mangaSourceExtra(shown.source)
					.build()
			},
			imageLoader = imageLoader,
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier
				.width(40.dp)
				.height(56.dp)
				.clip(RoundedCornerShape(6.dp)),
		)
		Spacer(Modifier.width(12.dp))
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = item.seed.title,
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			val choice = item.choice
			Text(
				text = when {
					item.candidates == null -> item.seed.source.getTitle(context)
					choice != null -> {
						val chapters = choice.chaptersCount()
						"${item.seed.source.getTitle(context)} → ${choice.source.getTitle(context)} · " +
							pluralStringResource(R.plurals.chapters, chapters, chapters)
					}

					item.candidates.isEmpty() -> stringResource(R.string.nothing_found)
					else -> stringResource(R.string.skipped)
				},
				style = MaterialTheme.typography.bodySmall,
				color = if (item.candidates != null && choice == null) {
					MaterialTheme.colorScheme.error
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
		}
		if (item.candidates == null) {
			Spacer(Modifier.width(12.dp))
			LoadingIndicator(modifier = Modifier.size(32.dp))
		}
	}
}

@Composable
private fun MigrationProgressBanner(progress: AutoFixService.Progress) {
	Column {
		Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = stringResource(R.string.migration_in_progress),
					style = MaterialTheme.typography.titleSmall,
					// This screen has no Surface root, so LocalContentColor is still the black default.
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.weight(1f),
				)
				Text(
					text = stringResource(
						R.string.migration_progress_pattern,
						progress.done,
						progress.total,
					),
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Spacer(Modifier.height(10.dp))
			if (progress.total > 0) {
				LinearProgressIndicator(
					progress = { progress.done.toFloat() / progress.total },
					modifier = Modifier.fillMaxWidth(),
				)
			} else {
				LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
			}
			Spacer(Modifier.height(8.dp))
			Text(
				text = stringResource(
					R.string.migration_progress_summary,
					progress.fixed,
					progress.failed,
				),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
	}
}

@Composable
private fun SourceSectionHeader(
	title: String,
	isChecked: Boolean? = null,
	onToggle: (() -> Unit)? = null,
	topPadding: androidx.compose.ui.unit.Dp = 8.dp,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.let { if (onToggle != null) it.clickable(onClick = onToggle) else it }
			.padding(
				start = 24.dp,
				end = 16.dp,
				top = topPadding,
				bottom = 6.dp,
			),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleSmall,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.primary,
		)
		if (isChecked != null && onToggle != null) {
			Spacer(Modifier.weight(1f))
			Checkbox(
				checked = isChecked,
				onCheckedChange = { onToggle() },
			)
		}
	}
}

/** [source] null shows only the letter fallback (the extension is gone, so there is no favicon). */
@Composable
private fun SourceIcon(
	title: String,
	source: MangaSource?,
	iconUrl: String?,
	imageLoader: ImageLoader,
) {
	val context = LocalContext.current
	val fallbackPainter = remember(context, title) {
		DrawablePainter(
			FaviconDrawable(
				context = context,
				styleResId = R.style.FaviconDrawable_Small,
				name = title,
			),
		)
	}
	Box(
		modifier = Modifier
			.width(40.dp)
			.height(40.dp)
			.clip(RoundedCornerShape(8.dp)),
	) {
		if (source == null) {
			Image(
				painter = fallbackPainter,
				contentDescription = null,
				modifier = Modifier.fillMaxSize(),
			)
		} else {
			val request = remember(context, iconUrl, source) {
				ImageRequest.Builder(context)
					.data(iconUrl ?: source.faviconUri())
					.apply {
						if (iconUrl == null) {
							mangaSourceExtra(source)
							suppressCaptchaErrors()
						}
					}
					.build()
			}
			AsyncImage(
				model = request,
				imageLoader = imageLoader,
				contentDescription = null,
				error = fallbackPainter,
				fallback = fallbackPainter,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
		}
	}
}

@Composable
private fun SourceCheckboxRow(
	source: LibrarySourceOption,
	imageLoader: ImageLoader,
	isChecked: Boolean,
	onToggle: () -> Unit,
	onOpenManga: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = 64.dp)
			.clickable(onClick = onToggle)
			.padding(horizontal = 16.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		SourceIcon(
			title = source.title,
			source = if (source.isUnavailable) {
				null
			} else {
				remember(source.iconSourceKey, source.title) { MangaSource(source.iconSourceKey, source.title) }
			},
			iconUrl = source.iconUrl,
			imageLoader = imageLoader,
		)
		Spacer(Modifier.width(12.dp))
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = source.title,
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Row(
				modifier = Modifier.clickable(onClick = onOpenManga),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = pluralStringResource(R.plurals.manga_count, source.mangaCount, source.mangaCount),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.primary,
				)
				Spacer(Modifier.width(4.dp))
				Icon(
					painter = painterResource(R.drawable.ic_chevron_right),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.width(8.dp).height(12.dp),
				)
			}
		}
		Spacer(Modifier.width(12.dp))
		Checkbox(
			checked = isChecked,
			onCheckedChange = { onToggle() },
		)
	}
}

@Composable
private fun SourceDivider() {
	HorizontalDivider(
		modifier = Modifier.padding(start = 68.dp, end = 16.dp),
		thickness = 1.dp,
		color = MaterialTheme.colorScheme.outlineVariant,
	)
}
