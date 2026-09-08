package org.koitharu.kotatsu.settings.override

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo

/** What the tracker can hand over, in the order the editor shows those fields. */
enum class ImportField(val titleResId: Int) {
	COVER(R.string.cover),
	TITLE(R.string.name),
	DESCRIPTION(R.string.description),
}

/**
 * Picks what to pull from a linked tracker: which tracker, then which of its fields. The values
 * only land in the editor's fields, so this sheet never has a destructive outcome — Save does.
 *
 * A field the tracker has nothing for is shown greyed and cannot be picked, which is more honest
 * than hiding it: it explains why the import left that field alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerImportSheet(
	manga: Manga,
	trackers: List<ScrobblingInfo>,
	imageLoader: ImageLoader,
	onImport: (ScrobblingInfo, Set<ImportField>) -> Unit,
	onDismiss: () -> Unit,
) {
	if (trackers.isEmpty()) {
		return
	}
	var selectedTracker by remember { mutableStateOf(trackers.first().scrobbler) }
	val tracker = trackers.firstOrNull { it.scrobbler == selectedTracker } ?: trackers.first()
	// Re-seeded per tracker: a field one tracker has nothing for may well be filled on the next.
	var fields by remember(tracker) { mutableStateOf(tracker.availableFields()) }

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
		shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER),
		containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
	) {
		Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
			Text(
				text = stringResource(R.string.import_from_tracker),
				style = MaterialTheme.typography.headlineSmall,
				fontWeight = FontWeight.SemiBold,
			)
			Spacer(Modifier.height(6.dp))
			Text(
				text = stringResource(R.string.import_from_tracker_hint),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			if (trackers.size > 1) {
				Spacer(Modifier.height(16.dp))
				Row(
					modifier = Modifier.horizontalScroll(rememberScrollState()),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					trackers.forEach { info ->
						FilterChip(
							selected = info.scrobbler == tracker.scrobbler,
							onClick = { selectedTracker = info.scrobbler },
							label = { Text(stringResource(info.scrobbler.titleResId)) },
							leadingIcon = {
								Icon(
									painter = painterResource(info.scrobbler.iconResId),
									contentDescription = null,
									modifier = Modifier.size(18.dp),
								)
							},
						)
					}
				}
			}
			Spacer(Modifier.height(16.dp))
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				ImportField.entries.forEachIndexed { index, field ->
					val value = tracker.valueOf(field)
					val isChecked = field in fields
					FieldRow(
						manga = manga,
						field = field,
						value = value,
						coverUrl = tracker.coverUrl.takeIf { field == ImportField.COVER },
						isChecked = isChecked,
						imageLoader = imageLoader,
						shape = RoundedCornerShape(
							topStart = if (index == 0) 20.dp else 4.dp,
							topEnd = if (index == 0) 20.dp else 4.dp,
							bottomStart = if (index == ImportField.entries.lastIndex) 20.dp else 4.dp,
							bottomEnd = if (index == ImportField.entries.lastIndex) 20.dp else 4.dp,
						),
						onToggle = {
							fields = if (isChecked) fields - field else fields + field
						},
					)
				}
			}
			Spacer(Modifier.height(20.dp))
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.End,
				verticalAlignment = Alignment.CenterVertically,
			) {
				TextButton(onClick = onDismiss) {
					Text(stringResource(android.R.string.cancel))
				}
				Spacer(Modifier.width(8.dp))
				Button(
					onClick = { onImport(tracker, fields) },
					enabled = fields.isNotEmpty(),
				) {
					Text(stringResource(R.string._import))
				}
			}
		}
	}
}

@Composable
private fun FieldRow(
	manga: Manga,
	field: ImportField,
	value: String?,
	coverUrl: String?,
	isChecked: Boolean,
	imageLoader: ImageLoader,
	shape: RoundedCornerShape,
	onToggle: () -> Unit,
) {
	val isAvailable = !value.isNullOrBlank()
	Surface(
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		contentColor = MaterialTheme.colorScheme.onSurface,
	) {
		Row(
			modifier = Modifier
				.clickable(enabled = isAvailable, onClick = onToggle)
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (coverUrl != null && isAvailable) {
				val context = LocalContext.current
				AsyncImage(
					model = remember(coverUrl) {
						ImageRequest.Builder(context)
							.data(coverUrl)
							.crossfade(true)
							.mangaSourceExtra(manga.source)
							.build()
					},
					imageLoader = imageLoader,
					contentDescription = null,
					contentScale = ContentScale.Crop,
					modifier = Modifier
						.size(width = 34.dp, height = 48.dp)
						.clip(RoundedCornerShape(8.dp))
						.background(MaterialTheme.colorScheme.surfaceContainerHighest),
				)
				Spacer(Modifier.width(12.dp))
			}
			Column(Modifier.weight(1f)) {
				Text(
					text = stringResource(field.titleResId),
					style = MaterialTheme.typography.bodyLarge,
					color = if (isAvailable) {
						MaterialTheme.colorScheme.onSurface
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
				)
				// The cover's thumbnail is its own preview; a raw URL underneath adds nothing.
				val subtitle = if (isAvailable) {
					value.takeUnless { field == ImportField.COVER }
				} else {
					stringResource(R.string.not_available)
				}
				if (subtitle != null) {
					Text(
						text = subtitle,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
			Spacer(Modifier.width(8.dp))
			Checkbox(
				checked = isChecked,
				onCheckedChange = { onToggle() },
				enabled = isAvailable,
			)
		}
	}
}

private fun ScrobblingInfo.valueOf(field: ImportField): String? = when (field) {
	ImportField.COVER -> coverUrl
	ImportField.TITLE -> title
	ImportField.DESCRIPTION -> description?.toString()
}

private fun ScrobblingInfo.availableFields(): Set<ImportField> =
	ImportField.entries.filterTo(HashSet()) { !valueOf(it).isNullOrBlank() }

private val SHEET_CORNER = 28.dp
