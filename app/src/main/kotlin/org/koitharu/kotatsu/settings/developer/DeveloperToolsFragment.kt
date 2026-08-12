package org.koitharu.kotatsu.settings.developer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.main.ui.nav.DrawablePainter
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.groupItemShape

@AndroidEntryPoint
class DeveloperToolsFragment : BaseComposeSettingsFragment(R.string.developer_testing_tools) {

	private val viewModel by viewModels<DeveloperToolsViewModel>()
	private val router by lazy { AppRouter(this) }

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val state by viewModel.uiState.collectAsState()
				DeveloperToolsScreen(
					state = state,
					onRun = viewModel::runAll,
					onCancel = viewModel::cancel,
					onOpenExtension = { sourceId ->
						router.openList(MangaSource(sourceId), null, null)
					},
				)
			}
		}
	}
}

@Composable
private fun DeveloperToolsScreen(
	state: DeveloperToolsUiState,
	onRun: () -> Unit,
	onCancel: () -> Unit,
	onOpenExtension: (String) -> Unit,
) {
	val results = state.results
	val passed = results.count { it.status == DeveloperExtensionStatus.PASSED }
	val blocked = results.count { it.status == DeveloperExtensionStatus.BLOCKED }
	val errors = results.count { it.status == DeveloperExtensionStatus.ERROR }
	val tested = passed + blocked + errors
	val listState = rememberLazyListState()

	Surface(
		modifier = Modifier.fillMaxSize(),
		color = MaterialTheme.colorScheme.surface,
		contentColor = MaterialTheme.colorScheme.onSurface,
	) {
		LazyColumn(
			state = listState,
			modifier = Modifier
				.fillMaxSize()
				.nestedScroll(rememberNestedScrollInteropConnection()),
			contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
			verticalArrangement = Arrangement.Top,
		) {
		item {
			Column(
				modifier = Modifier.padding(bottom = 12.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				if (state.isRunning) {
					Text(
						text = stringResource(R.string.developer_tools_progress, state.completed, state.total),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.onSurface,
					)
					LinearProgressIndicator(
						progress = { if (state.total == 0) 0f else state.completed.toFloat() / state.total },
						modifier = Modifier.fillMaxWidth(),
					)
				} else if (results.isNotEmpty()) {
					Text(
						text = stringResource(
							R.string.developer_tools_summary,
							tested,
							passed,
							blocked,
							errors,
						),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.onSurface,
					)
				}

				Button(
					onClick = if (state.isRunning) onCancel else onRun,
					modifier = Modifier
						.fillMaxWidth()
						.height(52.dp),
					shape = CircleShape,
					colors = if (state.isRunning) {
						ButtonDefaults.buttonColors(
							containerColor = MaterialTheme.colorScheme.errorContainer,
							contentColor = MaterialTheme.colorScheme.onErrorContainer,
						)
					} else {
						ButtonDefaults.buttonColors(
							containerColor = MaterialTheme.colorScheme.primaryContainer,
							contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
						)
					},
					elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
				) {
					Icon(
						painter = painterResource(if (state.isRunning) R.drawable.ic_close else R.drawable.ic_play),
						contentDescription = null,
						modifier = Modifier.size(20.dp),
					)
					Spacer(Modifier.width(8.dp))
					Text(
						text = stringResource(
							when {
								state.isRunning -> R.string.cancel_tests
								else -> R.string.test_all_extensions
							},
						),
					)
				}

				state.errorMessage?.let { message ->
					Text(
						text = message,
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.error,
					)
				}
			}
		}

		if (results.isEmpty() && !state.isRunning && state.errorMessage == null) {
			item {
				EmptyResults(hasRun = state.hasRun)
			}
		}

		itemsIndexed(results, key = { _, item -> item.packageName }) { index, result ->
			ExtensionResultItem(
				result = result,
				shape = groupItemShape(index, results.size),
				modifier = Modifier.padding(bottom = if (index == results.lastIndex) 12.dp else 3.dp),
				onOpenExtension = onOpenExtension,
			)
		}
		}
	}
}

@Composable
private fun EmptyResults(hasRun: Boolean) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 40.dp, horizontal = 16.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Icon(
			painter = painterResource(R.drawable.ic_script),
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.size(40.dp),
		)
		Text(
			text = stringResource(when {
				hasRun -> R.string.developer_tools_no_extensions
				else -> R.string.developer_tools_empty_title
			}),
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onSurface,
		)
	}
}

@Composable
private fun ExtensionResultItem(
	result: DeveloperExtensionTestResult,
	shape: androidx.compose.ui.graphics.Shape,
	modifier: Modifier = Modifier,
	onOpenExtension: (String) -> Unit,
) {
	var expanded by rememberSaveable(result.packageName) { mutableStateOf(false) }
	val interactionSource = remember { MutableInteractionSource() }
	val iconInteractionSource = remember { MutableInteractionSource() }
	val statusColor = result.status.color()
	val hasDetails = result.stages.isNotEmpty()
	Surface(
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		modifier = modifier
			.fillMaxWidth()
			.animateContentSize()
			.let {
				if (hasDetails) {
					it.clickable(
						interactionSource = interactionSource,
						indication = null,
						role = Role.Button,
					) { expanded = !expanded }
				} else {
					it
				}
			},
	) {
		Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Image(
					painter = rememberExtensionIcon(result.packageName),
					contentDescription = result.extensionName,
					modifier = Modifier
						.size(40.dp)
						.clip(RoundedCornerShape(8.dp))
						.let { iconModifier ->
							result.sourceId?.let { sourceId ->
								iconModifier.clickable(
									interactionSource = iconInteractionSource,
									indication = null,
									role = Role.Button,
								) { onOpenExtension(sourceId) }
							} ?: iconModifier
						},
				)
				Spacer(Modifier.width(12.dp))
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = result.extensionName,
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
					Text(
						text = listOf(result.sourceName, result.language, formatDuration(result.durationMillis))
							.filter { it.isNotBlank() }
							.joinToString(" · "),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
					Row(
						horizontalArrangement = Arrangement.spacedBy(6.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Icon(
							painter = painterResource(result.status.icon()),
							contentDescription = null,
							tint = statusColor,
							modifier = Modifier.size(if (result.status == DeveloperExtensionStatus.RUNNING) 10.dp else 14.dp),
						)
						Text(
							text = result.status.label(),
							style = MaterialTheme.typography.labelMedium,
							color = statusColor,
						)
					}
				}
				if (hasDetails) {
					Icon(
						painter = painterResource(R.drawable.ic_expand_more),
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier
							.size(24.dp)
							.rotate(if (expanded) 180f else 0f),
					)
				}
			}

			if (expanded) {
				Spacer(Modifier.height(12.dp))
				HorizontalDivider()
				Spacer(Modifier.height(8.dp))
				result.stages.forEach { stage ->
					StageResultRow(stage)
				}
			}
		}
	}
}

@Composable
private fun rememberExtensionIcon(packageName: String): DrawablePainter {
	val context = LocalContext.current
	return remember(context, packageName) {
		val drawable = runCatching { context.packageManager.getApplicationIcon(packageName) }
			.getOrElse { context.packageManager.defaultActivityIcon }
			.mutate()
		DrawablePainter(drawable)
	}
}

@Composable
private fun StageResultRow(stage: DeveloperTestStageResult) {
	val color = stage.status.color()
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 6.dp),
		verticalAlignment = Alignment.Top,
	) {
		Icon(
			painter = painterResource(stage.status.icon()),
			contentDescription = null,
			tint = color,
			modifier = Modifier.size(18.dp),
		)
		Spacer(Modifier.width(10.dp))
		Column(modifier = Modifier.weight(1f)) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(
					text = stage.name,
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium,
					modifier = Modifier.weight(1f),
				)
				if (stage.durationMillis > 0) {
					Text(
						text = formatDuration(stage.durationMillis),
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			stage.message?.takeIf { it.isNotBlank() }?.let { message ->
				Text(
					text = message,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun DeveloperExtensionStatus.color(): Color = when (this) {
	DeveloperExtensionStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
	DeveloperExtensionStatus.RUNNING -> MaterialTheme.colorScheme.primary
	DeveloperExtensionStatus.PASSED -> MaterialTheme.colorScheme.primary
	DeveloperExtensionStatus.BLOCKED -> MaterialTheme.colorScheme.tertiary
	DeveloperExtensionStatus.ERROR -> MaterialTheme.colorScheme.error
}

private fun DeveloperExtensionStatus.icon(): Int = when (this) {
	DeveloperExtensionStatus.PENDING -> R.drawable.ic_timelapse
	DeveloperExtensionStatus.RUNNING -> R.drawable.ic_new
	DeveloperExtensionStatus.PASSED -> R.drawable.ic_check
	DeveloperExtensionStatus.BLOCKED -> R.drawable.ic_alert_outline
	DeveloperExtensionStatus.ERROR -> R.drawable.ic_error_small
}

@Composable
private fun DeveloperTestStageStatus.color(): Color = when (this) {
	DeveloperTestStageStatus.PASSED -> MaterialTheme.colorScheme.primary
	DeveloperTestStageStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
	DeveloperTestStageStatus.BLOCKED -> MaterialTheme.colorScheme.tertiary
	DeveloperTestStageStatus.FAILED -> MaterialTheme.colorScheme.error
}

private fun DeveloperTestStageStatus.icon(): Int = when (this) {
	DeveloperTestStageStatus.PASSED -> R.drawable.ic_check
	DeveloperTestStageStatus.SKIPPED -> R.drawable.ic_info_outline
	DeveloperTestStageStatus.BLOCKED -> R.drawable.ic_alert_outline
	DeveloperTestStageStatus.FAILED -> R.drawable.ic_error_small
}

@Composable
private fun DeveloperExtensionStatus.label(): String = stringResource(
	when (this) {
		DeveloperExtensionStatus.PENDING -> R.string.developer_status_pending
		DeveloperExtensionStatus.RUNNING -> R.string.developer_status_running
		DeveloperExtensionStatus.PASSED -> R.string.developer_status_passed
		DeveloperExtensionStatus.BLOCKED -> R.string.developer_status_blocked
		DeveloperExtensionStatus.ERROR -> R.string.developer_status_error
	},
)

@Composable
private fun DeveloperTestStageStatus.label(): String = stringResource(
	when (this) {
		DeveloperTestStageStatus.PASSED -> R.string.developer_status_passed
		DeveloperTestStageStatus.SKIPPED -> R.string.developer_status_skipped
		DeveloperTestStageStatus.BLOCKED -> R.string.developer_status_blocked
		DeveloperTestStageStatus.FAILED -> R.string.developer_status_error
	},
)

private fun formatDuration(durationMillis: Long): String = when {
	durationMillis < 1_000 -> "${durationMillis} ms"
	else -> String.format(java.util.Locale.getDefault(), "%.1f s", durationMillis / 1_000f)
}
