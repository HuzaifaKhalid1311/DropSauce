package org.koitharu.kotatsu.settings.sources.migration

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import android.view.View
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.databinding.SheetDuplicatesBinding
import org.koitharu.kotatsu.favourites.ui.duplicates.MangaChoiceCard
import org.koitharu.kotatsu.favourites.ui.duplicates.expandExpressively
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import javax.inject.Inject

/**
 * Review step of Migrate extensions: every match found for one title, as the duplicates sheet's
 * cards. Reads the screen's own view model, so nothing has to be passed in but the title's id.
 */
@AndroidEntryPoint
class MigrationMatchSheet : BaseAdaptiveSheet<SheetDuplicatesBinding>() {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by viewModels<BrokenSourcesMigrationViewModel>(ownerProducer = { requireParentFragment() })

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetDuplicatesBinding {
		return SheetDuplicatesBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetDuplicatesBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val seedId = requireArguments().getLong(ARG_SEED_ID)
		binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		binding.composeView.setContent {
			DropSauceTheme {
				val state by viewModel.state.collectAsState()
				val item = state.review.find { it.seed.id == seedId }
				if (item?.candidates != null) {
					MatchContent(
						item = item,
						imageLoader = coil,
						onPreview = { router.openDetails(it) },
						onChoose = { manga ->
							viewModel.chooseReplacement(seedId, manga)
							dismiss()
						},
						onClose = ::dismiss,
					)
				}
			}
		}
	}

	override fun onStart() {
		super.onStart()
		expandExpressively()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		viewBinding?.root?.updatePadding(bottom = insets.getInsets(typeMask).bottom)
		return insets.consume(v, typeMask, bottom = true)
	}

	companion object {

		private const val ARG_SEED_ID = "seed_id"
		const val TAG = "MigrationMatchSheet"

		fun newInstance(seedId: Long) = MigrationMatchSheet().apply {
			arguments = Bundle(1).apply { putLong(ARG_SEED_ID, seedId) }
		}
	}
}

@Composable
private fun MatchContent(
	item: ReviewItem,
	imageLoader: ImageLoader,
	onPreview: (Manga) -> Unit,
	onChoose: (Manga?) -> Unit,
	onClose: () -> Unit,
) {
	val context = LocalContext.current
	val seedChapters = item.seed.chaptersCount()
	Column(modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			FilledTonalIconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
				Icon(
					painter = painterResource(R.drawable.ic_close),
					contentDescription = stringResource(android.R.string.cancel),
					modifier = Modifier.size(20.dp),
				)
			}
			Column(
				modifier = Modifier
					.weight(1f)
					.padding(horizontal = 12.dp),
			) {
				Text(
					text = stringResource(R.string.migration_pick_match),
					style = MaterialTheme.typography.titleLarge,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					text = stringResource(
						R.string.migration_pick_match_summary,
						item.seed.title,
						item.seed.source.getTitle(context),
					),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				if (seedChapters > 0) {
					Text(
						text = pluralStringResource(R.plurals.chapters, seedChapters, seedChapters),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.primary,
						modifier = Modifier.padding(top = 2.dp),
					)
				}
			}
		}
		Spacer(Modifier.height(16.dp))
		LazyColumn(
			contentPadding = PaddingValues(horizontal = 16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
			modifier = Modifier.weight(1f, fill = false),
		) {
			items(items = item.candidates.orEmpty(), key = { it.id }) { candidate ->
				val chapters = candidate.chaptersCount()
				val isSelected = candidate.id == item.choice?.id
				MangaChoiceCard(
					manga = candidate,
					imageLoader = imageLoader,
					chaptersCount = chapters,
					// Positive: the match has that many more chapters than the entry it replaces.
					chaptersDiff = if (chapters == 0 || seedChapters == 0) 0 else chapters - seedChapters,
					progress = 0f,
					categories = emptyList(),
					enabled = true,
					isSelected = isSelected,
					onPreview = { onPreview(candidate) },
				) {
					FilledTonalButton(
						onClick = { onChoose(candidate) },
						enabled = !isSelected,
						shape = RoundedCornerShape(18.dp),
						contentPadding = PaddingValues(horizontal = 14.dp),
						modifier = Modifier.height(38.dp),
					) {
						Icon(
							painter = painterResource(R.drawable.ic_check),
							contentDescription = null,
							modifier = Modifier.size(18.dp),
						)
						Spacer(Modifier.width(6.dp))
						Text(
							text = stringResource(if (isSelected) R.string.migration_match_chosen else R.string.migration_use_match),
							style = MaterialTheme.typography.labelLarge,
							maxLines = 1,
						)
					}
				}
			}
		}
		Spacer(Modifier.height(16.dp))
		Button(
			onClick = { onChoose(null) },
			shape = RoundedCornerShape(28.dp),
			modifier = Modifier
				.padding(horizontal = 16.dp)
				.fillMaxWidth()
				.height(56.dp),
		) {
			Text(
				text = stringResource(R.string.migration_skip_title),
				style = MaterialTheme.typography.titleMedium,
			)
		}
	}
}
