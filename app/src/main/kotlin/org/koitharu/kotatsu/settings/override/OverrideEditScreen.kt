package org.koitharu.kotatsu.settings.override

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.stableMangaCoverKey
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty

/**
 * Manga override editor: pick a cover, then optionally retitle and rewrite the description.
 *
 * The cover section comes first because it is the only part with its own preview; both text fields
 * stay empty while no override exists, so an empty field always means "use whatever the source
 * says" and a later source update still comes through.
 */
@Composable
fun OverrideEditScreen(
	manga: Manga,
	coverUrl: String?,
	title: String,
	description: String,
	originalDescription: String?,
	isLoading: Boolean,
	error: String?,
	bottomInset: Dp,
	imageLoader: ImageLoader,
	onTitleChange: (String) -> Unit,
	onDescriptionChange: (String) -> Unit,
	onCoverPick: (CoverSource) -> Unit,
	onCoverReset: () -> Unit,
) {
	val hasCustomCover = !coverUrl.isNullOrEmpty()
	val imeInset = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
	// imePadding is placed BEFORE verticalScroll so it shrinks the scroll viewport itself - that is
	// what makes a focused field settle just above the keyboard. Applied after the scroll it would
	// only pad the content and leave the field under the keys. The IME inset already spans the
	// navigation bar, so the nav padding below applies only while the keyboard is hidden.
	Column(
		modifier = Modifier
			.fillMaxSize()
			.imePadding()
			.nestedScroll(rememberNestedScrollInteropConnection())
			.verticalScroll(rememberScrollState())
			.padding(start = 16.dp, end = 16.dp, top = 8.dp)
			.padding(bottom = (bottomInset - imeInset).coerceAtLeast(0.dp) + 24.dp),
	) {
		CoverHero(
			manga = manga,
			coverUrl = coverUrl.ifNullOrEmpty { manga.coverUrl },
			originalCoverUrl = manga.coverUrl.takeIf { hasCustomCover },
			imageLoader = imageLoader,
			onResetClick = onCoverReset,
		)
		Spacer(Modifier.height(16.dp))
		SectionLabel(stringResource(R.string.change_cover))
		CoverSourceGrid(enabled = !isLoading, onPick = onCoverPick)
		Spacer(Modifier.height(24.dp))
		SectionLabel(stringResource(R.string.change_info))
		OverrideTextField(
			value = title,
			onValueChange = onTitleChange,
			label = stringResource(R.string.name),
			placeholder = manga.title,
			enabled = !isLoading,
			singleLine = true,
		)
		Spacer(Modifier.height(12.dp))
		OverrideTextField(
			value = description,
			onValueChange = onDescriptionChange,
			label = stringResource(R.string.description),
			placeholder = originalDescription,
			enabled = !isLoading,
			singleLine = false,
		)
		Spacer(Modifier.height(12.dp))
		HintRow(text = stringResource(R.string.manga_override_hint))
		AnimatedVisibility(visible = error != null) {
			Text(
				text = error.orEmpty(),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.error,
				modifier = Modifier.padding(top = 12.dp, start = 12.dp, end = 12.dp),
			)
		}
	}
}

/** Where a custom cover can come from. Order matches the rows shown in the editor. */
enum class CoverSource(val titleResId: Int, val iconResId: Int) {
	GALLERY(R.string.cover_source_gallery, R.drawable.ic_images),
	FILE(R.string.cover_source_file, R.drawable.ic_folder_file),
	PAGE(R.string.cover_source_page, R.drawable.ic_grid),
	URL(R.string.cover_source_url, R.drawable.ic_web),
}

/** Group heading, matching the label above every settings group. */
@Composable
private fun SectionLabel(text: String) {
	Text(
		text = text.uppercase(),
		style = MaterialTheme.typography.labelMedium,
		fontWeight = FontWeight.SemiBold,
		color = MaterialTheme.colorScheme.primary,
		modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
	)
}

/** 2x2 block of cover sources; 4dp inner seams make the four tiles read as one card. */
@Composable
private fun CoverSourceGrid(enabled: Boolean, onPick: (CoverSource) -> Unit) {
	val sources = CoverSource.entries
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		for (row in 0..1) {
			Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
				for (column in 0..1) {
					val source = sources[row * 2 + column]
					CoverSourceTile(
						source = source,
						enabled = enabled,
						shape = RoundedCornerShape(
							topStart = if (row == 0 && column == 0) 24.dp else 4.dp,
							topEnd = if (row == 0 && column == 1) 24.dp else 4.dp,
							bottomStart = if (row == 1 && column == 0) 24.dp else 4.dp,
							bottomEnd = if (row == 1 && column == 1) 24.dp else 4.dp,
						),
						onClick = { onPick(source) },
						modifier = Modifier.weight(1f),
					)
				}
			}
		}
	}
}

@Composable
private fun CoverSourceTile(
	source: CoverSource,
	enabled: Boolean,
	shape: RoundedCornerShape,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		modifier = modifier,
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		contentColor = MaterialTheme.colorScheme.onSurface,
	) {
		Row(
			modifier = Modifier
				.clickable(enabled = enabled, onClick = onClick)
				.fillMaxWidth()
				.padding(vertical = 14.dp, horizontal = 16.dp),
			horizontalArrangement = Arrangement.Start,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				painter = painterResource(source.iconResId),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.size(22.dp),
			)
			Spacer(Modifier.width(10.dp))
			Text(
				text = stringResource(source.titleResId),
				style = MaterialTheme.typography.labelLarge,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
private fun CoverHero(
	manga: Manga,
	coverUrl: String?,
	originalCoverUrl: String?,
	imageLoader: ImageLoader,
	onResetClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.animateContentSize(),
		horizontalArrangement = Arrangement.Center,
		verticalAlignment = Alignment.CenterVertically,
	) {
		Cover(
			manga = manga,
			url = coverUrl,
			imageLoader = imageLoader,
			modifier = Modifier.size(width = 168.dp, height = 232.dp),
			cornerRadius = 24.dp,
		)
		if (originalCoverUrl != null) {
			Spacer(Modifier.width(20.dp))
			Column(horizontalAlignment = Alignment.CenterHorizontally) {
				Cover(
					manga = manga,
					url = originalCoverUrl,
					imageLoader = imageLoader,
					modifier = Modifier.size(width = 78.dp, height = 108.dp),
					cornerRadius = 16.dp,
				)
				Spacer(Modifier.height(6.dp))
				Text(
					text = stringResource(R.string.original_cover),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Spacer(Modifier.height(4.dp))
				Surface(
					shape = RoundedCornerShape(percent = 50),
					color = MaterialTheme.colorScheme.secondaryContainer,
					contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
				) {
					IconButton(onClick = onResetClick) {
						Icon(
							painter = painterResource(R.drawable.ic_revert),
							contentDescription = stringResource(R.string.use_default_cover),
							modifier = Modifier.size(20.dp),
						)
					}
				}
			}
		}
	}
}

@Composable
private fun Cover(
	manga: Manga,
	url: String?,
	imageLoader: ImageLoader,
	modifier: Modifier,
	cornerRadius: Dp,
) {
	val context = LocalContext.current
	AsyncImage(
		model = remember(url) {
			ImageRequest.Builder(context)
				.data(url)
				.crossfade(true)
				.mangaSourceExtra(manga.source)
				.stableMangaCoverKey(manga, url)
				.build()
		},
		imageLoader = imageLoader,
		contentDescription = null,
		contentScale = ContentScale.Crop,
		modifier = modifier
			.clip(RoundedCornerShape(cornerRadius))
			.background(MaterialTheme.colorScheme.surfaceContainerHighest),
	)
}

/**
 * Field whose placeholder shows the source's own value as a faint watermark. Empty means "keep the
 * source value", so the trailing revert button just clears the field.
 */
@Composable
private fun OverrideTextField(
	value: String,
	onValueChange: (String) -> Unit,
	label: String,
	placeholder: String?,
	enabled: Boolean,
	singleLine: Boolean,
) {
	val watermark = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
	OutlinedTextField(
		value = value,
		onValueChange = onValueChange,
		label = { Text(label) },
		placeholder = placeholder?.let {
			{
				Text(
					text = it,
					// A long description would otherwise push the collapsed field open.
					maxLines = if (singleLine) 1 else 3,
					style = MaterialTheme.typography.bodyLarge,
				)
			}
		},
		trailingIcon = {
			AnimatedVisibility(visible = value.isNotEmpty()) {
				IconButton(onClick = { onValueChange("") }) {
					Icon(
						painter = painterResource(R.drawable.ic_revert),
						contentDescription = stringResource(R.string.reset),
					)
				}
			}
		},
		enabled = enabled,
		singleLine = singleLine,
		shape = RoundedCornerShape(20.dp),
		colors = OutlinedTextFieldDefaults.colors(
			focusedPlaceholderColor = watermark,
			unfocusedPlaceholderColor = watermark,
		),
		modifier = Modifier
			.fillMaxWidth()
			// Grows with the text from a single line, then scrolls internally instead of pushing
			// the rest of the screen away.
			.then(if (singleLine) Modifier else Modifier.heightIn(min = 56.dp, max = 220.dp)),
	)
}

@Composable
private fun HintRow(text: String) {
	Row(
		modifier = Modifier.padding(horizontal = 12.dp),
		verticalAlignment = Alignment.Top,
	) {
		Icon(
			painter = painterResource(R.drawable.ic_info_outline),
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier
				.padding(top = 2.dp)
				.size(16.dp),
		)
		Spacer(Modifier.width(8.dp))
		Text(
			text = text,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}
