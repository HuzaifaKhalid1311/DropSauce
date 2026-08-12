package org.koitharu.kotatsu.settings.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import org.koitharu.kotatsu.main.ui.nav.rememberAnyDrawablePainter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.github.AppVersion
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.about.changelog.ChangelogFragment
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.developer.DeveloperToolsFragment

@AndroidEntryPoint
class AboutSettingsFragment : BaseComposeSettingsFragment(R.string.about) {

	private val viewModel by viewModels<AboutSettingsViewModel>()

	private var pendingLogContent: String? = null

	private val saveLogLauncher = registerForActivityResult(
		ActivityResultContracts.CreateDocument("text/plain"),
	) { uri: Uri? ->
		val content = pendingLogContent ?: return@registerForActivityResult
		pendingLogContent = null
		if (uri == null) return@registerForActivityResult
		try {
			requireContext().contentResolver.openOutputStream(uri)?.use { output ->
				output.write(content.toByteArray(Charsets.UTF_8))
			}
		} catch (_: Exception) {
			Snackbar.make(requireView(), R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
		}
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val isUpdateSupported by viewModel.isUpdateSupported.collectAsState()
				val isLoading by viewModel.isLoading.collectAsState()
				val isVerboseLogging by viewModel.isVerboseLogging.collectAsState()
				AboutScreen(
					appVersion = BuildConfig.VERSION_NAME,
					checkUpdatesEnabled = isUpdateSupported && !isLoading,
					isVerboseLogging = isVerboseLogging,
					onCheckUpdates = viewModel::checkForUpdates,
					onChangelog = ::openChangelog,
					onOpenLink = ::openLink,
					onVerboseLoggingToggle = viewModel::setVerboseLogging,
					onOpenDeveloperTools = ::openDeveloperTools,
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onUpdateAvailable.observeEvent(viewLifecycleOwner, ::onUpdateAvailable)
		viewModel.onExportLog.observeEvent(viewLifecycleOwner) { content ->
			pendingLogContent = content
			saveLogLauncher.launch("dropsauce_log_${System.currentTimeMillis()}.txt")
		}
	}

	private fun openChangelog() {
		(activity as? SettingsActivity)?.openFragment(
			ChangelogFragment::class.java,
			null,
			isFromRoot = false,
		)
	}

	private fun openDeveloperTools() {
		(activity as? SettingsActivity)?.openFragment(
			DeveloperToolsFragment::class.java,
			null,
			isFromRoot = false,
		)
	}

	private fun openLink(@StringRes urlRes: Int, titleRes: Int) {
		val opened = router.openExternalBrowser(getString(urlRes), getString(titleRes))
		if (!opened) {
			Snackbar.make(
				requireView(),
				R.string.operation_not_supported,
				Snackbar.LENGTH_SHORT,
			).show()
		}
	}

	private fun onUpdateAvailable(version: AppVersion?) {
		if (version == null) {
			Snackbar.make(requireView(), R.string.no_update_available, Snackbar.LENGTH_SHORT).show()
		} else {
			startActivity(Intent(requireContext(), AppUpdateActivity::class.java))
		}
	}
}

@Composable
private fun AboutScreen(
	appVersion: String,
	checkUpdatesEnabled: Boolean,
	isVerboseLogging: Boolean,
	onCheckUpdates: () -> Unit,
	onChangelog: () -> Unit,
	onOpenLink: (urlRes: Int, titleRes: Int) -> Unit,
	onVerboseLoggingToggle: (Boolean) -> Unit,
	onOpenDeveloperTools: () -> Unit,
) {
	val ctx = LocalContext.current
	SettingsScaffold {
		// Hero header: app icon + name + version chip — gives the About screen a sense of place
		// instead of being just another list of links.
		item { AboutHero(appVersion = appVersion) }
		item { Spacer(Modifier.height(16.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Updates") {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.check_for_updates),
						subtitle = ctx.getString(R.string.app_version, appVersion),
						icon = R.drawable.ic_app_update,
						shape = pos.shape,
						enabled = checkUpdatesEnabled,
						onClick = onCheckUpdates,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.changelog),
						subtitle = stringResource(R.string.changelog_summary),
						icon = R.drawable.ic_history,
						shape = pos.shape,
						onClick = onChangelog,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Links") {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.user_manual),
						subtitle = stringResource(R.string.url_user_manual),
						icon = R.drawable.ic_book_page,
						shape = pos.shape,
						onClick = { onOpenLink(R.string.url_user_manual, R.string.user_manual) },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.source_code),
						subtitle = stringResource(R.string.url_github),
						icon = R.drawable.ic_open_external,
						shape = pos.shape,
						onClick = { onOpenLink(R.string.url_github, R.string.source_code) },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.discord),
						subtitle = stringResource(R.string.url_discord_web),
						icon = R.drawable.ic_discord,
						shape = pos.shape,
						onClick = { onOpenLink(R.string.url_discord_web, R.string.discord) },
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Diagnostics") {
				item { pos ->
					SwitchSettingsItem(
						title = "Verbose logging",
						subtitle = if (isVerboseLogging) {
							"Recording — turn off to save log as .txt"
						} else {
							"Off by default to preserve performance"
						},
						icon = R.drawable.ic_script,
						shape = pos.shape,
						checked = isVerboseLogging,
						onCheckedChange = onVerboseLoggingToggle,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.developer_testing_tools),
						subtitle = stringResource(R.string.developer_testing_tools_summary),
						icon = R.drawable.ic_timer_run,
						shape = pos.shape,
						onClick = onOpenDeveloperTools,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}

@Composable
private fun AboutHero(appVersion: String) {
	val cs = MaterialTheme.colorScheme
	val decorColor = cs.onPrimaryContainer.copy(alpha = 0.16f)
	val decorColorStrong = cs.onPrimaryContainer.copy(alpha = 0.22f)
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(28.dp),
		color = cs.primaryContainer,
	) {
		Box(modifier = Modifier.fillMaxWidth()) {
			val infiniteTransition = rememberInfiniteTransition(label = "HeroShapes")

			val cookieRotation = infiniteTransition.animateFloat(
				initialValue = 0f,
				targetValue = 360f,
				animationSpec = infiniteRepeatable(
					animation = tween(18000, easing = LinearEasing),
					repeatMode = RepeatMode.Restart
				),
				label = "cookieRotation"
			)

			val pillOffsetX = infiniteTransition.animateFloat(
				initialValue = -25f,
				targetValue = 25f,
				animationSpec = infiniteRepeatable(
					animation = tween(10000, easing = EaseInOut),
					repeatMode = RepeatMode.Reverse
				),
				label = "pillOffsetX"
			)

			val pillOffsetY = infiniteTransition.animateFloat(
				initialValue = -15f,
				targetValue = 15f,
				animationSpec = infiniteRepeatable(
					animation = tween(12000, easing = EaseInOut),
					repeatMode = RepeatMode.Reverse
				),
				label = "pillOffsetY"
			)

			val ghostScale = infiniteTransition.animateFloat(
				initialValue = 0.85f,
				targetValue = 1.15f,
				animationSpec = infiniteRepeatable(
					animation = tween(8000, easing = EaseInOut),
					repeatMode = RepeatMode.Reverse
				),
				label = "ghostScale"
			)

			// Ghost-ish M3 Expressive shapes (ghost, 4-sided cookie, pill) scattered behind the content,
			// clipped by the Surface's rounded shape. The centered app icon sits around
			// (width/2, ~90dp); shapes are kept to the edges/corners so they never touch it.
			Canvas(modifier = Modifier.matchParentSize()) {
				val unit = size.minDimension
				// 4-sided cookie, top-right corner, rotating smoothly
				rotate(degrees = cookieRotation.value, pivot = Offset(size.width * 0.93f, size.height * 0.12f)) {
					drawCookie(
						centerX = size.width * 0.93f,
						centerY = size.height * 0.12f,
						radius = unit * 0.22f,
						color = decorColorStrong,
					)
				}
				// Pill, upright, left edge, moving around freely
				drawPill(
					centerX = size.width * 0.05f + pillOffsetX.value,
					centerY = size.height * 0.46f + pillOffsetY.value,
					width = unit * 0.15f,
					height = unit * 0.44f,
					rotationDeg = -16f,
					color = decorColor,
				)
				// Ghost-ish shape, diagonal, bottom-right, expanding and shrinking
				scale(scale = ghostScale.value, pivot = Offset(size.width * 0.85f, size.height * 0.85f)) {
					drawGhost(
						centerX = size.width * 0.85f,
						centerY = size.height * 0.85f,
						radius = unit * 0.28f,
						color = decorColor,
					)
				}
			}
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(top = 36.dp, bottom = 28.dp, start = 24.dp, end = 24.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			// Logo: rounded-squircle tile on a soft tonal plate, so the adaptive icon reads
			// as a proper app mark rather than a tiny circle.
			Box(
				modifier = Modifier
					.size(108.dp)
					.clip(RoundedCornerShape(34.dp))
					.background(cs.onPrimaryContainer.copy(alpha = 0.10f)),
				contentAlignment = Alignment.Center,
			) {
				Image(
					painter = rememberAnyDrawablePainter(org.koitharu.kotatsu.R.mipmap.ic_launcher),
					contentDescription = null,
					modifier = Modifier
						.size(88.dp)
						.clip(RoundedCornerShape(26.dp)),
				)
			}
			Text(
				text = stringResource(R.string.app_name),
				style = MaterialTheme.typography.headlineLarge,
				color = cs.onPrimaryContainer,
				fontWeight = FontWeight.Bold,
			)
			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				AboutMetaPill(
					icon = R.drawable.ic_info_outline,
					text = "v$appVersion",
				)
				AboutMetaPill(
					icon = R.drawable.ic_github,
					text = "HuzaifaKhalid1311",
				)
			}
		}
		}
	}
}

@Composable
private fun AboutMetaPill(
	@androidx.annotation.DrawableRes icon: Int,
	text: String,
) {
	val cs = MaterialTheme.colorScheme
	Surface(
		shape = RoundedCornerShape(50),
		color = cs.onPrimaryContainer.copy(alpha = 0.16f),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				painter = painterResource(icon),
				contentDescription = null,
				tint = cs.onPrimaryContainer,
				modifier = Modifier.size(15.dp),
			)
			Text(
				text = text,
				style = MaterialTheme.typography.labelLarge,
				color = cs.onPrimaryContainer,
				fontWeight = FontWeight.Medium,
			)
		}
	}
}

/**
 * Draws a standard M3-Expressive 4-sided "cookie" shape (a `RoundedPolygon` star), built with
 * androidx.graphics.shapes — the same primitive the Material 3 shape set uses.
 */
private fun DrawScope.drawCookie(
	centerX: Float,
	centerY: Float,
	radius: Float,
	color: Color,
) {
	val polygon = RoundedPolygon.star(
		numVerticesPerRadius = 4,
		radius = radius,
		innerRadius = radius * 0.82f,
		rounding = CornerRounding(radius * 0.5f),
		innerRounding = CornerRounding(radius * 0.5f),
		centerX = centerX,
		centerY = centerY,
	)
	drawPath(polygon.toComposePath(), color)
}

/** Draws a generic M3-Expressive "ghost-ish" blob shape. */
private fun DrawScope.drawGhost(
	centerX: Float,
	centerY: Float,
	radius: Float,
	color: Color,
) {
	val polygon = RoundedPolygon.star(
		numVerticesPerRadius = 5,
		radius = radius,
		innerRadius = radius * 0.7f,
		rounding = CornerRounding(radius * 0.5f),
		innerRounding = CornerRounding(radius * 0.4f),
		centerX = centerX,
		centerY = centerY,
	)
	drawPath(polygon.toComposePath(), color)
}

/** Converts an androidx.graphics.shapes [RoundedPolygon] into a Compose [Path]. */
private fun RoundedPolygon.toComposePath(): Path {
	val path = Path()
	val cubicList = cubics
	if (cubicList.isEmpty()) return path
	val first = cubicList.first()
	path.moveTo(first.anchor0X, first.anchor0Y)
	for (c in cubicList) {
		path.cubicTo(c.control0X, c.control0Y, c.control1X, c.control1Y, c.anchor1X, c.anchor1Y)
	}
	path.close()
	return path
}

/** Draws a single M3-Expressive "pill" (stadium) shape, optionally rotated. */
private fun DrawScope.drawPill(
	centerX: Float,
	centerY: Float,
	width: Float,
	height: Float,
	rotationDeg: Float,
	color: Color,
) {
	rotate(degrees = rotationDeg, pivot = Offset(centerX, centerY)) {
		drawRoundRect(
			color = color,
			topLeft = Offset(centerX - width / 2f, centerY - height / 2f),
			size = Size(width, height),
			cornerRadius = CornerRadius(height / 2f, height / 2f),
		)
	}
}
