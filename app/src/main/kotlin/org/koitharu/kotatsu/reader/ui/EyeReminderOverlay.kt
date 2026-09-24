package org.koitharu.kotatsu.reader.ui

import android.content.res.Resources
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import kotlinx.coroutines.delay
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.playReminderHaptic

fun eyeReminderDuration(resources: Resources, seconds: Int): String =
	resources.getQuantityString(R.plurals.minutes, seconds / 60, seconds / 60)

/**
 * Reading time shared by every reader session, so hopping between chapters doesn't restart the
 * count. Only a real break away from the reader resets it (process death does too).
 */
object EyeReminderClock {

	var readMs = 0L
	private var leftAt = 0L

	fun onReaderShown() {
		if (leftAt != 0L && SystemClock.elapsedRealtime() - leftAt >= BREAK_MS) {
			readMs = 0
		}
		leftAt = 0
	}

	fun onReaderHidden() {
		leftAt = SystemClock.elapsedRealtime()
	}

	private const val BREAK_MS = 5 * 60_000L
}

// M3 "emphasized decelerate": fast start, long gentle landing.
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Full-screen "rest your eyes" notice shown over the reader after continuous reading. */
@Composable
fun EyeReminderOverlay(
	visible: Boolean,
	intervalSeconds: Int,
	onDismiss: () -> Unit,
) {
	val colors = MaterialTheme.colorScheme
	AnimatedVisibility(
		visible = visible,
		enter = fadeIn(tween(350)),
		exit = fadeOut(tween(300, delayMillis = 80)),
	) {
		BackHandler(onBack = onDismiss)
		val context = LocalContext.current
		LaunchedEffect(Unit) {
			delay(140) // lands with the shape's pop
			context.playReminderHaptic()
		}
		// Staggered entrance: each piece has its own delay and its own kind of motion.
		val shapePop = rememberEntrance(80, spring(0.5f, 360f))
		val shapeMorph = rememberEntrance(80, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessVeryLow))
		val eyeOpen = rememberEntrance(460, spring(0.45f, Spring.StiffnessMediumLow))
		val title = rememberEntrance(560, tween(550, easing = EmphasizedDecelerate))
		val body = rememberEntrance(700, tween(600, easing = EmphasizedDecelerate))
		val button = rememberEntrance(880, spring(0.55f, Spring.StiffnessMediumLow))
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(
					Brush.verticalGradient(
						0f to colors.surfaceContainerLowest.copy(alpha = 0.94f),
						1f to colors.primaryContainer.copy(alpha = 0.94f),
					),
				)
				// Swallow touches so the page underneath doesn't turn.
				.clickable(interactionSource = null, indication = null, onClick = {})
				.windowInsetsPadding(WindowInsets.safeDrawing)
				.padding(horizontal = 32.dp),
			contentAlignment = Alignment.Center,
		) {
			Column(
				modifier = Modifier
					.widthIn(max = 420.dp)
					.animateEnterExit(
						enter = EnterTransition.None,
						exit = scaleOut(tween(300), targetScale = 0.92f) + fadeOut(tween(250)),
					),
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				EyeBadge(pop = shapePop, morph = shapeMorph, eyeOpen = eyeOpen)
				Spacer(Modifier.height(40.dp))
				Text(
					text = stringResource(R.string.eye_reminder_title),
					style = MaterialTheme.typography.headlineMedium,
					color = colors.onSurface,
					textAlign = TextAlign.Center,
					modifier = Modifier.graphicsLayer {
						alpha = title.value
						translationY = (1f - title.value) * 32.dp.toPx()
					},
				)
				Spacer(Modifier.height(12.dp))
				Text(
					text = stringResource(
						R.string.eye_reminder_text,
						eyeReminderDuration(context.resources, intervalSeconds),
					),
					style = MaterialTheme.typography.bodyLarge,
					color = colors.onSurfaceVariant,
					textAlign = TextAlign.Center,
					modifier = Modifier.graphicsLayer {
						alpha = body.value
						translationY = (1f - body.value) * 20.dp.toPx()
					},
				)
				Spacer(Modifier.height(40.dp))
				Button(
					onClick = onDismiss,
					shapes = ButtonDefaults.shapes(),
					contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
					modifier = Modifier
						.heightIn(ButtonDefaults.MediumContainerHeight)
						.graphicsLayer {
							alpha = button.value.coerceIn(0f, 1f)
							scaleX = 0.7f + 0.3f * button.value
							scaleY = scaleX
						},
				) {
					Text(
						text = stringResource(R.string.dismiss),
						style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight),
					)
				}
			}
		}
	}
}

/** Animates 0 → 1 once, [delayMs] after entering composition. Read `.value` in draw/layer lambdas. */
@Composable
private fun rememberEntrance(delayMs: Long, spec: AnimationSpec<Float>): Animatable<Float, *> {
	val progress = remember { Animatable(0f) }
	LaunchedEffect(Unit) {
		delay(delayMs)
		progress.animateTo(1f, spec)
	}
	return progress
}

/** A circle that pops in and morphs into a slowly spinning cookie, then a blinking eye opens on it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EyeBadge(
	pop: Animatable<Float, *>,
	morph: Animatable<Float, *>,
	eyeOpen: Animatable<Float, *>,
) {
	val color = MaterialTheme.colorScheme.primary
	val shape = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
	val path = remember { Path() }
	val motion = rememberInfiniteTransition(label = "eye_badge")
	val spin by motion.animateFloat(
		initialValue = 0f,
		targetValue = 360f,
		animationSpec = infiniteRepeatable(tween(30_000, easing = LinearEasing)),
		label = "spin",
	)
	val breath by motion.animateFloat(
		initialValue = 1f,
		targetValue = 1.05f,
		animationSpec = infiniteRepeatable(tween(2_400), RepeatMode.Reverse),
		label = "breath",
	)
	val lid by motion.animateFloat(
		initialValue = 1f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(
			keyframes {
				durationMillis = 4_200
				1f at 3_700
				0.08f at 3_820
				1f at 3_960
			},
		),
		label = "blink",
	)
	Box(modifier = Modifier.size(168.dp), contentAlignment = Alignment.Center) {
		Canvas(
			Modifier
				.size(168.dp)
				.graphicsLayer {
					scaleX = pop.value * breath
					scaleY = scaleX
					// Unwinds a quarter turn while it morphs, then keeps a slow spin.
					rotationZ = spin - 90f * (1f - morph.value)
				},
		) {
			shape.toPath(morph.value, path)
			scale(size.width, size.height, pivot = Offset.Zero) {
				drawPath(path, color)
			}
		}
		Icon(
			painter = painterResource(R.drawable.ic_visibility),
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onPrimary,
			modifier = Modifier
				.size(72.dp)
				.graphicsLayer {
					alpha = eyeOpen.value.coerceIn(0f, 1f)
					scaleY = eyeOpen.value * lid
				},
		)
	}
}
