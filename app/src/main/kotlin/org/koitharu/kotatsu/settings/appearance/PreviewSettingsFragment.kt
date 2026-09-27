package org.koitharu.kotatsu.settings.appearance

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DetailsUiMode
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.details.ui.ChaptersPill
import org.koitharu.kotatsu.details.ui.FavouriteButton
import org.koitharu.kotatsu.details.ui.Pill
import org.koitharu.kotatsu.details.ui.SCREEN_PADDING
import org.koitharu.kotatsu.details.ui.SectionCard
import org.koitharu.kotatsu.details.ui.dockGlow
import org.koitharu.kotatsu.details.ui.luminanceIsLight
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SettingsSearchHighlight
import org.koitharu.kotatsu.settings.compose.SliderSettingsItem
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberDetailsBackdropBlurPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref

@AndroidEntryPoint
class PreviewSettingsFragment : BaseComposeSettingsFragment(R.string.details_appearance) {

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				DetailsAppearanceScreen()
			}
		}
	}
}

@Composable
private fun DetailsAppearanceScreen() {
	val ctx = LocalContext.current
	var uiMode by rememberStringPref(AppSettings.KEY_DETAILS_UI, DetailsUiMode.COMPACT.name)
	var backdrop by rememberBooleanPref(AppSettings.KEY_DETAILS_BACKDROP, true)
	var backdropBlurAmount by rememberDetailsBackdropBlurPref(AppSettings.KEY_DETAILS_BACKDROP_BLUR_AMOUNT, 2)

	SettingsScaffold {
		item {
			DetailsStylePicker(
				selected = uiMode,
				onSelect = { uiMode = it },
				backdrop = backdrop,
				blurAmount = backdropBlurAmount,
			)
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.details_backdrop),
						subtitle = stringResource(R.string.details_backdrop_summary),
						checked = backdrop,
						onCheckedChange = { backdrop = it },
						icon = R.drawable.ic_images,
						shape = pos.shape,
					)
				}
				item { pos ->
					SliderSettingsItem(
						title = stringResource(R.string.details_backdrop_blur),
						value = backdropBlurAmount,
						valueFrom = 0,
						valueTo = 2,
						stepSize = 1,
						onValueChange = { backdropBlurAmount = it },
						icon = R.drawable.ic_images,
						shape = pos.shape,
						enabled = backdrop,
						valueLabel = { valVal ->
							when (valVal) {
								0 -> ctx.getString(R.string.details_backdrop_blur_none)
								1 -> ctx.getString(R.string.details_backdrop_blur_light)
								else -> ctx.getString(R.string.details_backdrop_blur_default)
							}
						}
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}

// The preview is laid out at a real phone's size (412×892dp, 19.5:9) using the details screen's own
// measurements and components, then scaled down to fit — so its proportions always match the real thing.
private val PHONE_WIDTH = 412.dp
private val PHONE_HEIGHT = 892.dp
private val STATUS_BAR = 36.dp
private val NAV_BAR = 24.dp
private val BEZEL = 5.dp

/**
 * The details style setting itself: one live mini phone per style, tap to pick — the same pattern as
 * the colour scheme picker (bordered card, check on the active one), but with radio semantics.
 */
@Composable
private fun DetailsStylePicker(
	selected: String,
	onSelect: (String) -> Unit,
	backdrop: Boolean,
	blurAmount: Int,
) {
	val title = stringResource(R.string.details_ui)
	// This card replaces the old "Details UI" row, so it answers a settings-search jump to it; it
	// already sits at the top of the page, so there is nothing to scroll to.
	val pendingHighlight by SettingsSearchHighlight.pendingTitle.collectAsState()
	LaunchedEffect(pendingHighlight) {
		if (pendingHighlight == title) SettingsSearchHighlight.consume(title)
	}
	val haptic = rememberHapticEffect()
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(24.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
	) {
		Column(Modifier.padding(vertical = 16.dp)) {
			Text(
				text = title,
				style = MaterialTheme.typography.titleMedium,
				modifier = Modifier.padding(horizontal = 16.dp),
			)
			Spacer(Modifier.height(16.dp))
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp)
					.selectableGroup(),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				// Compact first (the default for new installs), then Centralized.
				for (mode in arrayOf(DetailsUiMode.COMPACT, DetailsUiMode.EXPRESSIVE)) {
					DetailsStyleOption(
						label = stringResource(
							if (mode == DetailsUiMode.COMPACT) R.string.details_ui_compact else R.string.details_ui_expressive,
						),
						centered = mode != DetailsUiMode.COMPACT,
						selected = mode.name == selected,
						backdrop = backdrop,
						blurAmount = blurAmount,
						onClick = {
							haptic(HapticEffect.CONFIRM)
							onSelect(mode.name)
						},
						modifier = Modifier.weight(1f),
					)
				}
			}
		}
	}
}

@Composable
private fun DetailsStyleOption(
	label: String,
	centered: Boolean,
	selected: Boolean,
	backdrop: Boolean,
	blurAmount: Int,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val scheme = MaterialTheme.colorScheme
	val borderColor by animateColorAsState(
		targetValue = if (selected) scheme.primary else scheme.outline.copy(alpha = 0.3f),
		label = "detailsStyleBorder",
	)
	Column(
		modifier = modifier.selectable(
			selected = selected,
			onClick = onClick,
			role = Role.RadioButton,
			interactionSource = null,
			indication = null,
		),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
			val screenWidth = min(maxWidth - BEZEL * 2, 200.dp)
			val scale = screenWidth / PHONE_WIDTH
			Surface(
				modifier = Modifier.size(screenWidth + BEZEL * 2, PHONE_HEIGHT * scale + BEZEL * 2),
				shape = RoundedCornerShape(26.dp),
				color = scheme.surfaceContainerHighest,
				border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
			) {
				Box(
					modifier = Modifier
						.padding(BEZEL)
						.clip(RoundedCornerShape(26.dp - BEZEL)),
					contentAlignment = Alignment.Center,
				) {
					Box(
						Modifier
							.requiredSize(PHONE_WIDTH, PHONE_HEIGHT)
							.graphicsLayer {
								scaleX = scale
								scaleY = scale
							},
					) {
						PreviewScreen(centered = centered, backdrop = backdrop, blurAmount = blurAmount)
					}
					// ponytail: swallows touches so the real (clickable) components inside stay inert;
					// the tap still reaches the option's selectable
					Box(Modifier.fillMaxSize().pointerInput(Unit) {})
				}
			}
		}
		Spacer(Modifier.height(8.dp))
		Row(verticalAlignment = Alignment.CenterVertically) {
			RadioButton(selected = selected, onClick = null)
			Spacer(Modifier.width(8.dp))
			Text(
				text = label,
				style = MaterialTheme.typography.labelLarge,
				fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
				color = if (selected) scheme.primary else scheme.onSurface,
				maxLines = 1,
			)
		}
	}
}

@Composable
private fun PreviewScreen(centered: Boolean, backdrop: Boolean, blurAmount: Int) {
	val scheme = MaterialTheme.colorScheme
	val accent = scheme.primary
	Box(
		Modifier
			.fillMaxSize()
			.background(scheme.surface),
	) {
		if (backdrop) PreviewBackdrop(blurAmount)

		Column(
			modifier = Modifier
				.fillMaxWidth()
				.wrapContentHeight(Alignment.Top, unbounded = true),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			// Same offsets as DetailsExpressiveScreen: hero clears the status bar + translucent top bar.
			Spacer(Modifier.height(STATUS_BAR + if (centered) 84.dp else 72.dp))
			if (centered) {
				CenteredHero(accent)
				Spacer(Modifier.height(20.dp))
				FavouriteButton(
					label = stringResource(R.string.add_to_favourites),
					isFavourite = false,
					accent = accent,
					onClick = {},
				)
			} else {
				CompactHero(accent)
			}
			Spacer(Modifier.height(8.dp))
			PreviewProgressCard(accent)
			PreviewDescriptionCard(accent)
			PreviewTags(accent)
		}

		PreviewStatusBar()
		PreviewTopBar()
		Column(
			modifier = Modifier
				.align(Alignment.BottomEnd)
				.padding(end = SCREEN_PADDING, bottom = NAV_BAR + 16.dp)
				.dockGlow(scheme.surface),
			horizontalAlignment = Alignment.End,
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			ChaptersPill(count = 120, onClick = {})
			PreviewReadFab(accent)
		}
		Bar(
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(bottom = 10.dp)
				.size(108.dp, 4.dp),
			color = scheme.onSurface.copy(alpha = 0.35f),
		)
	}
}

@Composable
private fun PreviewBackdrop(blurAmount: Int) {
	val scheme = MaterialTheme.colorScheme
	val surface = scheme.surface
	// Mirrors ExpressiveBackdrop: the cover art, blurred, fading into the surface.
	val blurDp = when (blurAmount) {
		0 -> 0.dp
		1 -> 20.dp
		else -> 40.dp
	}
	Box(
		Modifier
			.fillMaxWidth()
			.height(480.dp),
	) {
		Box(
			Modifier
				.fillMaxSize()
				.then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurDp > 0.dp) Modifier.blur(blurDp) else Modifier)
				.coverArt(scheme),
		)
		Box(
			Modifier
				.fillMaxSize()
				.background(
					Brush.verticalGradient(
						0f to surface.copy(alpha = 0.30f),
						0.4f to surface.copy(alpha = 0.55f),
						0.78f to surface.copy(alpha = 0.94f),
						1f to surface,
					),
				),
		)
	}
}

@Composable
private fun CenteredHero(accent: Color) {
	val scheme = MaterialTheme.colorScheme
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = SCREEN_PADDING),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		PreviewCover(Modifier.size(158.dp, 236.dp), corner = 24.dp)
		Spacer(Modifier.height(20.dp))
		TextBar(width = 260.dp, lineHeight = 36.dp, barHeight = 24.dp, color = scheme.onSurface)
		TextBar(width = 170.dp, lineHeight = 36.dp, barHeight = 24.dp, color = scheme.onSurface)
		Spacer(Modifier.height(8.dp))
		TextBar(width = 120.dp, lineHeight = 20.dp, barHeight = 12.dp, color = accent)
		Spacer(Modifier.height(16.dp))
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Pill(text = stringResource(MangaState.ONGOING.titleResId), accent = accent)
			PreviewSourcePill()
		}
	}
}

@Composable
private fun CompactHero(accent: Color) {
	val scheme = MaterialTheme.colorScheme
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = SCREEN_PADDING)
			.height(178.dp),
	) {
		PreviewCover(
			Modifier
				.width(120.dp)
				.fillMaxHeight(),
			corner = 20.dp,
		)
		Spacer(Modifier.width(16.dp))
		Column(
			Modifier
				.weight(1f)
				.fillMaxHeight(),
		) {
			TextBar(width = 190.dp, lineHeight = 32.dp, barHeight = 22.dp, color = scheme.onSurface)
			Spacer(Modifier.height(8.dp))
			TextBar(width = 110.dp, lineHeight = 20.dp, barHeight = 12.dp, color = accent)
			Spacer(Modifier.height(12.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Pill(text = stringResource(MangaState.ONGOING.titleResId), accent = accent)
				PreviewSourcePill(Modifier.weight(1f, fill = false))
			}
			Spacer(Modifier.weight(1f))
			FavouriteButton(
				label = stringResource(R.string.add_to_favourites),
				isFavourite = false,
				accent = accent,
				onClick = {},
				horizontalPadding = 0.dp,
			)
		}
	}
}

@Composable
private fun PreviewProgressCard(accent: Color) {
	SectionCard {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Icon(
				painter = painterResource(R.drawable.ic_read),
				contentDescription = null,
				tint = accent,
				modifier = Modifier.size(22.dp),
			)
			Spacer(Modifier.width(12.dp))
			Text(
				text = stringResource(R.string.chapter_d_of_d, 42, 120),
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
			)
			Text(
				text = stringResource(R.string.percent_string_pattern, "35"),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
				color = accent,
			)
		}
		Spacer(Modifier.height(14.dp))
		LinearWavyProgressIndicator(
			progress = { 0.35f },
			color = accent,
			trackColor = accent.copy(alpha = 0.22f),
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

@Composable
private fun PreviewDescriptionCard(accent: Color) {
	val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
	SectionCard {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = stringResource(R.string.description),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
			)
			Pill(text = "4.6", accent = accent, highlighted = true) {
				Icon(
					painter = painterResource(R.drawable.ic_star_small),
					contentDescription = null,
					tint = accent,
					modifier = Modifier.size(15.dp),
				)
			}
		}
		Spacer(Modifier.height(10.dp))
		for (fraction in floatArrayOf(1f, 0.94f, 1f, 0.88f, 0.6f)) {
			Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
				Bar(
					Modifier
						.fillMaxWidth(fraction)
						.height(10.dp),
					lineColor,
				)
			}
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewTags(accent: Color) {
	FlowRow(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = SCREEN_PADDING, vertical = 7.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		for (width in intArrayOf(74, 96, 62, 88, 110, 70, 84, 66)) {
			Surface(shape = RoundedCornerShape(15.dp), color = accent.copy(alpha = 0.16f)) {
				Box(Modifier.size(width.dp, 38.dp), contentAlignment = Alignment.Center) {
					Bar(Modifier.size((width - 28).dp, 10.dp), accent.copy(alpha = 0.55f))
				}
			}
		}
	}
}

@Composable
private fun PreviewSourcePill(modifier: Modifier = Modifier) {
	val content = MaterialTheme.colorScheme.onSurfaceVariant
	Surface(
		shape = RoundedCornerShape(50),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = modifier,
	) {
		Row(
			modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			Icon(
				painter = painterResource(R.drawable.ic_manga_source),
				contentDescription = null,
				tint = content,
				modifier = Modifier.size(16.dp),
			)
			Bar(Modifier.size(64.dp, 9.dp), content.copy(alpha = 0.45f))
		}
	}
}

@Composable
private fun PreviewReadFab(accent: Color) {
	val onColor = if (accent.luminanceIsLight()) Color.Black else Color.White
	Surface(shape = RoundedCornerShape(24.dp), color = accent, shadowElevation = 6.dp) {
		Row(
			modifier = Modifier
				.height(56.dp)
				.padding(start = 22.dp, end = 16.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				painter = painterResource(R.drawable.ic_play),
				contentDescription = null,
				tint = onColor,
				modifier = Modifier.size(28.dp),
			)
			Spacer(Modifier.width(10.dp))
			Text(
				text = stringResource(R.string._continue),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.SemiBold,
				color = onColor,
				maxLines = 1,
			)
			Spacer(Modifier.width(14.dp))
			Bar(Modifier.size(1.dp, 24.dp), onColor.copy(alpha = 0.3f))
			Spacer(Modifier.width(12.dp))
			Icon(
				painter = painterResource(R.drawable.ic_expand_more),
				contentDescription = null,
				tint = onColor,
				modifier = Modifier.size(22.dp),
			)
		}
	}
}

@Composable
private fun PreviewStatusBar() {
	val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(STATUS_BAR)
			.padding(horizontal = 24.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Bar(Modifier.size(38.dp, 12.dp), color)
		Spacer(Modifier.weight(1f))
		Bar(Modifier.size(14.dp, 12.dp), color)
		Bar(Modifier.size(14.dp, 12.dp), color)
		Bar(Modifier.size(24.dp, 12.dp), color)
	}
}

/**
 * Mirrors TopBarNavigationButtonStyler (the treatment every toolbar gets): a translucent tonal circle
 * for back, and a connected group of tonal segments for the actions (share + overflow).
 */
@Composable
private fun PreviewTopBar() {
	val cell = dimensionResource(R.dimen.top_bar_navigation_button_size)
	val segmentWidth = dimensionResource(R.dimen.top_bar_action_segment_width)
	val inner = dimensionResource(R.dimen.top_bar_action_segment_inner_corner)
	val edge = dimensionResource(R.dimen.top_bar_navigation_button_margin_start)
	val outer = cell / 2
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = STATUS_BAR, start = edge, end = edge)
			.height(64.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		TopBarButton(R.drawable.ic_arrow_back, cell, RoundedCornerShape(outer))
		Spacer(Modifier.weight(1f))
		TopBarButton(R.drawable.ic_share, segmentWidth, RoundedCornerShape(outer, inner, inner, outer))
		Spacer(Modifier.width(dimensionResource(R.dimen.top_bar_action_segment_spacing)))
		TopBarButton(R.drawable.ic_more_vert, segmentWidth, RoundedCornerShape(inner, outer, outer, inner))
	}
}

@Composable
private fun TopBarButton(@DrawableRes icon: Int, width: Dp, shape: Shape) {
	val ctx = LocalContext.current
	Box(
		modifier = Modifier
			.size(width, dimensionResource(R.dimen.top_bar_navigation_button_size))
			.background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f), shape),
		contentAlignment = Alignment.Center,
	) {
		Icon(
			painter = painterResource(icon),
			contentDescription = null,
			tint = Color(ctx.getThemeColor(R.attr.colorTopBarIcon)),
			modifier = Modifier.size(dimensionResource(R.dimen.top_bar_action_icon_size)),
		)
	}
}

@Composable
private fun PreviewCover(modifier: Modifier, corner: Dp) {
	val scheme = MaterialTheme.colorScheme
	Surface(
		shape = RoundedCornerShape(corner),
		color = scheme.surfaceVariant,
		tonalElevation = 4.dp,
		shadowElevation = 16.dp,
		modifier = modifier,
	) {
		Box(
			Modifier
				.fillMaxSize()
				.coverArt(scheme),
		)
	}
}

/** Abstract stand-in artwork built from the theme colours; the backdrop blurs the same art. */
private fun Modifier.coverArt(scheme: ColorScheme) = clipToBounds().drawBehind {
	drawRect(
		Brush.linearGradient(
			listOf(scheme.tertiary, scheme.primary, scheme.primaryContainer),
			start = Offset.Zero,
			end = Offset(size.width, size.height),
		),
	)
	drawCircle(
		color = scheme.tertiaryContainer,
		radius = size.width * 0.26f,
		center = Offset(size.width * 0.68f, size.height * 0.28f),
	)
	drawCircle(
		color = scheme.onPrimaryContainer.copy(alpha = 0.35f),
		radius = size.width * 0.95f,
		center = Offset(size.width * 0.15f, size.height * 1.3f),
	)
}

/** A skeleton text line: [barHeight] pill centred in a [lineHeight] row, like the real text's line box. */
@Composable
private fun TextBar(width: Dp, lineHeight: Dp, barHeight: Dp, color: Color) {
	Box(Modifier.height(lineHeight), contentAlignment = Alignment.Center) {
		Bar(Modifier.size(width, barHeight), color.copy(alpha = color.alpha * 0.85f))
	}
}

@Composable
private fun Bar(modifier: Modifier, color: Color) {
	Box(
		modifier
			.clip(RoundedCornerShape(50))
			.background(color),
	)
}
