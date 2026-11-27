package org.koitharu.kotatsu.core.ui.list

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import androidx.graphics.shapes.Morph
import kotlinx.coroutines.delay
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

/**
 * Simple Material 3 Expressive "Last position" floating pill with spring entrance, press squircle
 * morph, and a 9-sided cookie icon badge.
 */
class ListCheckpointView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

	var onJumpClick: (() -> Unit)? = null
	var onDisableClick: (() -> Unit)? = null

	private var showToken by mutableIntStateOf(0)
	private var isTargetVisible by mutableStateOf(false)
	private var isTravelling by mutableStateOf(false)

	val isShowing: Boolean
		get() = isTargetVisible

	private val composeView = ComposeView(context).apply {
		clipChildren = false
		clipToPadding = false
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val visibleState = remember { MutableTransitionState(false) }
				visibleState.targetState = isTargetVisible

				LaunchedEffect(visibleState.isIdle, visibleState.currentState, isTargetVisible) {
					if (visibleState.isIdle && !visibleState.currentState && !isTargetVisible) {
						this@ListCheckpointView.isVisible = false
					}
				}

				AnimatedVisibility(
					visibleState = visibleState,
					enter = scaleIn(
						animationSpec = spring(dampingRatio = 0.56f, stiffness = Spring.StiffnessMediumLow),
						initialScale = 0.72f,
					) + slideInVertically(
						animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
					) { it * 2 / 3 } + fadeIn(
						animationSpec = spring(stiffness = Spring.StiffnessMedium),
					),
					exit = scaleOut(
						animationSpec = spring(stiffness = Spring.StiffnessMedium),
						targetScale = 0.8f,
					) + slideOutVertically(
						animationSpec = spring(stiffness = Spring.StiffnessMedium),
					) { it / 2 } + fadeOut(
						animationSpec = spring(stiffness = Spring.StiffnessHigh),
					),
				) {
					CheckpointPill(
						token = showToken,
						enabled = isTargetVisible && !isTravelling,
						isTravelling = isTravelling,
						onAutoHide = { hide() },
						onClick = { onJumpClick?.invoke() },
						onLongClick = { onDisableClick?.invoke() },
					)
				}
			}
		}
	}

	init {
		clipChildren = false
		clipToPadding = false
		isVisible = false
		addView(
			composeView,
			LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
		)
	}

	fun show() {
		showToken++
		isTravelling = false
		isTargetVisible = true
		isVisible = true
	}

	fun setTravelling() {
		if (isTargetVisible) {
			isTravelling = true
		}
	}

	fun hide(immediate: Boolean = false) {
		isTargetVisible = false
		isTravelling = false
		if (immediate) {
			isVisible = false
		}
	}
}

private const val AUTO_HIDE_DELAY_MS = 5000L

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CheckpointPill(
	token: Int,
	enabled: Boolean,
	isTravelling: Boolean,
	onAutoHide: () -> Unit,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
) {
	val colors = MaterialTheme.colorScheme
	val haptic = rememberHapticEffect()
	val interactionSource = remember { MutableInteractionSource() }
	val isPressed by interactionSource.collectIsPressedAsState()
	val animatorsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }

	LaunchedEffect(token, isTravelling, isPressed) {
		if (!isTravelling && !isPressed) {
			delay(AUTO_HIDE_DELAY_MS)
			onAutoHide()
		}
	}

	// The badge keeps turning while shown and speeds up while jumping; the angle is read only at draw time.
	val spinSpeed by animateFloatAsState(
		targetValue = if (isTravelling) 180f else 45f,
		animationSpec = spring(stiffness = Spring.StiffnessLow),
		label = "checkpointSpinSpeed",
	)
	var spinAngle by remember { mutableFloatStateOf(0f) }
	LaunchedEffect(animatorsEnabled) {
		if (!animatorsEnabled) return@LaunchedEffect
		var last = withFrameNanos { it }
		while (true) {
			withFrameNanos { now ->
				spinAngle = (spinAngle + spinSpeed * (now - last) / 1_000_000_000f) % 360f
				last = now
			}
		}
	}

	val corner by animateDpAsState(
		targetValue = if (isPressed) 14.dp else 24.dp,
		animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
		label = "checkpointCorner",
	)
	val pressScale by animateFloatAsState(
		targetValue = if (isPressed) 0.94f else 1f,
		animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow),
		label = "checkpointScale",
	)
	val cookieMorph by animateFloatAsState(
		targetValue = if (isPressed) 0.1f else 1f,
		animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow),
		label = "cookieMorph",
	)

	val cookie = MaterialShapes.Cookie9Sided
	val morph = remember { Morph(MaterialShapes.Circle, cookie) }
	val path = remember { Path() }
	val matrix = remember { Matrix() }

	Box(
		modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
		contentAlignment = Alignment.Center,
	) {
		Surface(
			shape = RoundedCornerShape(corner),
			color = colors.primaryContainer,
			contentColor = colors.onPrimaryContainer,
			shadowElevation = 6.dp,
			border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.22f)),
			modifier = Modifier.graphicsLayer {
				scaleX = pressScale
				scaleY = pressScale
			},
		) {
			Row(
				modifier = Modifier
					.combinedClickable(
						enabled = enabled,
						interactionSource = interactionSource,
						indication = ripple(),
						role = Role.Button,
						onClickLabel = stringResource(R.string.list_checkpoint_settings),
						onClick = {
							haptic(HapticEffect.CONFIRM)
							onClick()
						},
						onLongClickLabel = stringResource(R.string.disable),
						onLongClick = {
							haptic(HapticEffect.LONG_PRESS)
							onLongClick()
						},
					)
					.height(48.dp)
					.padding(start = 9.dp, end = 16.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Box(
					contentAlignment = Alignment.Center,
					modifier = Modifier
						.size(30.dp)
						.drawBehind {
							val progress = cookieMorph.coerceIn(0f, 1f)
							path.rewind()
							morph.toPath(progress, path)
							// A 9-sided cookie's centre isn't the middle of its bounds: shift it there so it spins in place around the arrow.
							matrix.reset()
							matrix.scale(size.width, size.height)
							matrix.translate((0.5f - cookie.centerX) * progress, (0.5f - cookie.centerY) * progress)
							path.transform(matrix)
							rotate(spinAngle + cookieMorph * 20f) {
								drawPath(path, colors.primary)
							}
						},
				) {
					Icon(
						painter = painterResource(R.drawable.ic_jump_back),
						contentDescription = null,
						tint = colors.onPrimary,
						// The glyph sits ~0.5dp above the middle of its 18dp box.
						modifier = Modifier
							.offset(y = 0.5.dp)
							.size(18.dp),
					)
				}
				Spacer(Modifier.width(9.dp))
				Text(
					text = stringResource(R.string.list_checkpoint),
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.SemiBold,
					color = colors.onPrimaryContainer,
					maxLines = 1,
				)
			}
		}
	}
}
