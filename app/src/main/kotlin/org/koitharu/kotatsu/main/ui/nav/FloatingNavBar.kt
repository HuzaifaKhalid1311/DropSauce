package org.koitharu.kotatsu.main.ui.nav

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect

data class FloatingNavBarItem(
	@IdRes val id: Int,
	val titleRes: Int,
	@DrawableRes val icon: Int,
	val badgeCount: Int = 0,
)

data class FloatingNavBarColors(
	val container: Int,
	val selectedContainer: Int,
	val selectedContent: Int,
	val unselectedContent: Int,
)

// Motion comes from the app-wide Expressive MotionScheme (see DropSauceTheme) rather than
// hand-tuned springs, so the nav bar moves on the same beat as sheets, dialogs and the details
// dock. Spatial specs animate anything that moves or resizes; effects specs animate colour and
// alpha, which M3 deliberately runs on a different (non-overshooting) curve.
private val MotionSizeSpec: FiniteAnimationSpec<IntSize>
	@Composable get() = MaterialTheme.motionScheme.defaultSpatialSpec()

private val MotionAlphaSpec: FiniteAnimationSpec<Float>
	@Composable get() = MaterialTheme.motionScheme.defaultEffectsSpec()

private val MotionColorSpec: FiniteAnimationSpec<Color>
	@Composable get() = MaterialTheme.motionScheme.defaultEffectsSpec()

@Composable
fun FloatingNavBar(
	items: List<FloatingNavBarItem>,
	selectedId: Int,
	showLabels: Boolean,
	colors: FloatingNavBarColors,
	onItemSelected: (Int) -> Unit,
	onItemReselected: (Int) -> Unit,
	modifier: Modifier = Modifier,
	showContinue: Boolean = false,
	onContinueClick: () -> Unit = {},
	onContinueLongClick: () -> Unit = {},
) {
	if (items.isEmpty()) return
	val cs = MaterialTheme.colorScheme
	val barColor = Color(colors.container)
	val haptic = rememberHapticEffect()

	Row(
		modifier = modifier.wrapContentWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Surface(
			modifier = Modifier
				.shadow(8.dp, RoundedCornerShape(50))
				.wrapContentWidth(),
			shape = RoundedCornerShape(50),
			color = barColor,
			contentColor = cs.onSurface,
		) {
			Row(
				modifier = Modifier
					.heightIn(min = 64.dp)
					.padding(horizontal = 8.dp, vertical = 8.dp)
					// Smoothly relayout siblings when one pill grows/shrinks horizontally.
					.animateContentSize(animationSpec = MotionSizeSpec),
				horizontalArrangement = Arrangement.spacedBy(4.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				items.forEach { item ->
					FloatingNavItem(
						item = item,
						selected = item.id == selectedId,
						showLabel = showLabels,
						colors = colors,
						onClick = {
							// Selection is routed through the host NavigationBarView, whose
							// listener (MainNavigationDelegate) already performs the CONFIRM
							// haptic — firing one here too would double-buzz. Reselecting the
							// current tab stays silent.
							if (item.id == selectedId) {
								onItemReselected(item.id)
							} else {
								onItemSelected(item.id)
							}
						},
					)
				}
			}
		}
		// A standalone, pill-coloured circular "continue reading" button living next to the
		// floating bar (like the search FAB in Tomato). It animates in/out smoothly and slides
		// along as the bar resizes, so it always feels part of the same floating toolbar.
		AnimatedVisibility(
			visible = showContinue,
			enter = fadeIn(animationSpec = MotionAlphaSpec) +
				expandHorizontally(animationSpec = MotionSizeSpec, expandFrom = Alignment.Start),
			exit = fadeOut(animationSpec = MotionAlphaSpec) +
				shrinkHorizontally(animationSpec = MotionSizeSpec, shrinkTowards = Alignment.Start),
		) {
			FloatingContinueButton(
				colors = colors,
				onClick = {
					haptic(HapticEffect.CONFIRM)
					onContinueClick()
				},
				onLongClick = {
					haptic(HapticEffect.LONG_PRESS)
					onContinueLongClick()
				},
			)
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FloatingContinueButton(
	colors: FloatingNavBarColors,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
) {
	val container by animateColorAsState(
		targetValue = Color(colors.selectedContainer),
		animationSpec = MotionColorSpec,
		label = "continueContainer",
	)
	val content by animateColorAsState(
		targetValue = Color(colors.selectedContent),
		animationSpec = MotionColorSpec,
		label = "continueContent",
	)
	val label = stringResource(R.string.continue_reading)
	val tooltipState = rememberTooltipState()
	TooltipBox(
		positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
		tooltip = { PlainTooltip { Text(label) } },
		state = tooltipState,
	) {
		Surface(
			// M3 Expressive square FAB: 16dp corner on a 56dp container
			shape = RoundedCornerShape(16.dp),
			color = container,
			contentColor = content,
			shadowElevation = 8.dp,
			modifier = Modifier
				.size(56.dp)
				.semantics { contentDescription = label },
		) {
			Box(
				contentAlignment = Alignment.Center,
				modifier = Modifier.combinedClickable(
					onClick = onClick,
					onLongClick = onLongClick,
				),
			) {
				Icon(
					painter = painterResource(R.drawable.ic_read),
					contentDescription = null,
					tint = content,
					modifier = Modifier.size(24.dp),
				)
			}
		}
	}
}

@Composable
private fun FloatingNavItem(
	item: FloatingNavBarItem,
	selected: Boolean,
	showLabel: Boolean,
	colors: FloatingNavBarColors,
	onClick: () -> Unit,
) {
	val container by animateColorAsState(
		targetValue = if (selected) {
			Color(colors.selectedContainer)
		} else {
			Color.Transparent
		},
		animationSpec = MotionColorSpec,
		label = "navItemContainer",
	)
	val content by animateColorAsState(
		targetValue = if (selected) {
			Color(colors.selectedContent)
		} else {
			Color(colors.unselectedContent)
		},
		animationSpec = MotionColorSpec,
		label = "navItemContent",
	)
	val title = stringResource(item.titleRes)
	val interactionSource = remember { MutableInteractionSource() }

	Box(
		modifier = Modifier
			.height(48.dp)
			.background(color = container, shape = CircleShape)
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
			)
			.semantics {
				this.selected = selected
				role = Role.Tab
				contentDescription = title
			},
		contentAlignment = Alignment.Center,
	) {
		Row(
			modifier = Modifier.padding(horizontal = 14.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Center,
		) {
			BadgedBox(
				badge = {
					if (item.badgeCount > 0) {
						Badge { Text(text = if (item.badgeCount > 99) "99+" else item.badgeCount.toString()) }
					} else if (item.badgeCount < 0) {
						Badge()
					}
				},
			) {
				// Use a real ImageView so the AnimatedStateListDrawable's enter/leave morphs
				// (avd_*_enter / avd_*_leave) actually tick. Painting an AVD onto Compose's
				// canvas via a custom Painter doesn't reliably drive the platform animator —
				// ImageView does, because it's the same path the native BottomNavigationView uses.
				NavIcon(
					resId = item.icon,
					selected = selected,
					tint = content,
					modifier = Modifier.size(24.dp),
				)
			}
			AnimatedVisibility(
				visible = selected && showLabel,
				enter = expandHorizontally(
					animationSpec = MotionSizeSpec,
					expandFrom = Alignment.Start,
				) + fadeIn(animationSpec = MotionAlphaSpec),
				exit = shrinkHorizontally(
					animationSpec = MotionSizeSpec,
					shrinkTowards = Alignment.Start,
				) + fadeOut(animationSpec = MotionAlphaSpec),
			) {
				Text(
					text = title,
					color = content,
					fontSize = 14.sp,
					lineHeight = 20.sp,
					maxLines = 1,
					modifier = Modifier.padding(start = 8.dp),
				)
			}
		}
	}
}

private val SELECTOR_STATE_CHECKED = intArrayOf(android.R.attr.state_checked)
private val SELECTOR_STATE_UNCHECKED = intArrayOf(-android.R.attr.state_checked)

/**
 * Renders a selector drawable (state-list with `<animated-selector>` transitions) inside
 * Compose by hosting a real [ImageView]. We use ImageView rather than a custom [Painter]
 * because `AnimatedVectorDrawable`'s animator pipeline doesn't reliably tick when painted
 * onto Compose's generic canvas — wrapping in a View matches the path the platform
 * `BottomNavigationView` uses, where these morphs are known to work.
 *
 * The [selected] flag drives the drawable state via [ImageView.setImageState], which is
 * what triggers the `<transition>` AVD between the normal and checked items.
 */
@Composable
private fun NavIcon(
	@DrawableRes resId: Int,
	selected: Boolean,
	tint: Color,
	modifier: Modifier = Modifier,
) {
	AndroidView(
		modifier = modifier,
		factory = { ctx ->
			ImageView(ctx).apply {
				scaleType = ImageView.ScaleType.FIT_CENTER
				setImageResource(resId)
				// Prime initial state without a transition. Setting state twice (empty,
				// then the real state) suppresses the first-paint morph that would
				// otherwise fire on inflate.
				setImageState(IntArray(0), false)
				jumpDrawablesToCurrentState()
			}
		},
		update = { iv ->
			val targetResId = when {
				resId == R.drawable.ic_explore_selector && selected -> R.drawable.ic_explore_checked
				resId == R.drawable.ic_explore_selector -> R.drawable.ic_explore_normal
				else -> resId
			}
			val resourceChanged = iv.tag != targetResId
			if (resourceChanged) {
				iv.setImageResource(targetResId)
				iv.tag = targetResId
			}
			val targetState = if (selected) SELECTOR_STATE_CHECKED else SELECTOR_STATE_UNCHECKED
			if (resourceChanged || iv.isSelected != selected) {
				iv.isSelected = selected
				iv.isActivated = selected
				iv.setImageState(targetState, false)
			}
			val tintColor = tint.toArgb()
			iv.imageTintList = ColorStateList.valueOf(tintColor)
			iv.drawable?.mutate()?.setTint(tintColor)
			iv.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
		},
	)
}
