package org.koitharu.kotatsu.core.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.main.ui.nav.rememberAnyDrawablePainter
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

// Same as the settings back button (top_bar_navigation_button_size / top_bar_action_icon_size).
private val CornerButtonSize = 46.dp
private val HeaderIconSize = 36.dp
private val CornerIconSize = 22.dp
private val PillButtonHeight = 56.dp
private val DialogHorizontalMargin = 24.dp
private val DialogMaxWidth = 420.dp
private val CardPadding = 24.dp

/**
 * Hosts a Compose [content] inside a transparent [ComponentDialog], styled as the app's
 * M3 Expressive popup. [content] receives a `dismiss` callback.
 */
fun showComposeDialog(
	context: Context,
	cancelable: Boolean = true,
	onCancel: (() -> Unit)? = null,
	content: @Composable (dismiss: () -> Unit) -> Unit,
): ComponentDialog {
	val dialog = ComponentDialog(context)
	dialog.setContentView(
		ComposeView(context).apply {
			setContent {
				DropSauceTheme {
					content { dialog.dismiss() }
				}
			}
		},
	)
	dialog.setCancelable(cancelable)
	if (onCancel != null) {
		dialog.setOnCancelListener { onCancel() }
	}
	dialog.window?.run {
		setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
		setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
	}
	// Plain dialog, not a DialogFragment — dismiss with the host to avoid a leaked window.
	val owner = context.findActivity() as? LifecycleOwner
	if (owner != null) {
		val observer = object : DefaultLifecycleObserver {
			override fun onDestroy(owner: LifecycleOwner) = dialog.dismiss()
		}
		owner.lifecycle.addObserver(observer)
		dialog.setOnDismissListener { owner.lifecycle.removeObserver(observer) }
	}
	dialog.show()
	return dialog
}

/**
 * Replays a tap far outside the hosting dialog's window, so the dialog applies its own rules: it closes
 * only when cancelable and closing on outside taps (a Compose `Dialog` routes it to onDismissRequest).
 * Down and up both go out: older Android closes on the down, newer on the up; a closed dialog ignores the rest.
 */
private fun View.tapOutsideDialog() {
	val dialog = findViewTreeOnBackPressedDispatcherOwner() as? Dialog ?: return
	for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
		if (!dialog.isShowing) return
		val event = MotionEvent.obtain(0L, 0L, action, -10_000f, -10_000f, 0)
		dialog.onTouchEvent(event)
		event.recycle()
	}
}

/**
 * Circular tonal icon action pinned to the top-right corner of [ExpressiveDialogCard] (e.g. a cross
 * to cancel/close or a tick to confirm/finish). Expands smoothly in width and morphs shape on contact.
 */
class DialogCornerAction(
	val icon: Painter,
	val label: String,
	val enabled: Boolean = true,
	val onClick: () -> Unit,
)

/**
 * M3 Expressive popup card in the settings page's colours turned inside out: the card is the settings
 * rows' `surfaceContainer`, its option rows the page's `background`. Three zones:
 * - **Pinned header**: optional bare [icon] (accent, or error when [destructive]), then the
 *   left-aligned [title], which wraps as needed, and one tonal
 *   [cornerAction] (or a custom [headerTrailing]) on the end edge.
 * - **Scrollable body**: optional [message] plus [content], scrolling between the pinned header and
 *   footer with subtle draw-only dividers fading in while content overflows.
 * - **Pinned footer**: optional [footer] (typically [ExpressiveButtonGrid] or [ExpressivePillButton])
 *   that stays visible at the bottom regardless of how tall the body grows.
 */
@Composable
fun ExpressiveDialogCard(
	icon: Painter?,
	title: String,
	modifier: Modifier = Modifier,
	message: String? = null,
	destructive: Boolean = false,
	cornerAction: DialogCornerAction? = null,
	headerTrailing: (@Composable RowScope.() -> Unit)? = null,
	pinnedTopContent: (@Composable ColumnScope.() -> Unit)? = null,
	footer: (@Composable ColumnScope.() -> Unit)? = null,
	content: (@Composable ColumnScope.() -> Unit)? = null,
) {
	val colors = MaterialTheme.colorScheme
	val scrollState = rememberScrollState()
	val hasBody = message != null || content != null
	val topDividerAlphaState = animateFloatAsState(
		targetValue = if (hasBody && scrollState.canScrollBackward) 0.65f else 0f,
		label = "topScrollDivider",
	)
	val bottomDividerAlphaState = animateFloatAsState(
		targetValue = if (hasBody && footer != null && scrollState.canScrollForward) 0.65f else 0f,
		label = "bottomScrollDivider",
	)

	val hostView = LocalView.current
	Box(
		modifier = modifier
			.fillMaxWidth()
			// The window spans the screen width, so taps beside the card land inside it: treat them as outside.
			.pointerInput(Unit) { detectTapGestures { hostView.tapOutsideDialog() } }
			.padding(horizontal = DialogHorizontalMargin, vertical = 16.dp),
		contentAlignment = Alignment.Center,
	) {
		Surface(
			// Concentric with the round corner button: its radius plus the card padding.
			shape = RoundedCornerShape(CornerButtonSize / 2 + CardPadding),
			color = colors.surfaceContainer,
			modifier = Modifier
				.fillMaxWidth()
				.widthIn(max = DialogMaxWidth)
				// Taps on the card itself stop here instead of reaching the outside handler above.
				.pointerInput(Unit) { detectTapGestures { } },
		) {
			Column(
				modifier = Modifier.padding(CardPadding),
			) {
				// ── Pinned Header ────────────────────────────────────────────────
				// Icon and button stay pinned to the top corners; the icon and the title's first line are
				// centered on the button, and a long title only grows downward.
				// Between titleLarge and headlineSmall, with headlineSmall's heavier weight.
				// Untrimmed, centered line box: the first line is exactly lineHeight tall, so the padding below
				// centers it on the button (Compose trims the first line's top by default).
				val titleStyle = MaterialTheme.typography.titleLarge.copy(
					fontSize = 23.sp,
					lineHeight = 30.sp,
					fontWeight = FontWeight.SemiBold,
					lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
				)
				val titleTopPadding = with(LocalDensity.current) {
					((CornerButtonSize - titleStyle.lineHeight.toDp()) / 2).coerceAtLeast(0.dp)
				}
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.zIndex(2f),
					verticalAlignment = Alignment.Top,
				) {
					if (icon != null) {
						Icon(
							painter = icon,
							contentDescription = null,
							tint = if (destructive) colors.error else colors.primary,
							modifier = Modifier
								.padding(top = (CornerButtonSize - HeaderIconSize) / 2)
								.size(HeaderIconSize),
						)
						Spacer(Modifier.width(12.dp))
					}
					Text(
						text = title,
						style = titleStyle,
						color = colors.onSurface,
						modifier = Modifier
							.weight(1f)
							.padding(top = titleTopPadding),
					)
					if (headerTrailing != null || cornerAction != null) {
						Spacer(Modifier.width(12.dp))
						headerTrailing?.invoke(this)
						if (cornerAction != null) {
							DialogCornerButton(action = cornerAction, destructive = destructive)
						}
					}
				}

				if (pinnedTopContent != null) {
					Spacer(Modifier.height(16.dp))
					pinnedTopContent()
				}

				// ── Scrollable Body ──────────────────────────────────────────────
				// Scroll dividers are drawn directly over the viewport edges without adding or
				// removing layout nodes, so reaching the top/bottom never alters the scroll
				// container's height or interrupts Android's stretch overscroll.
				if (hasBody) {
					Spacer(Modifier.height(16.dp))
					val dividerColor = colors.outlineVariant
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.weight(1f, fill = false)
							.drawWithContent {
								drawContent()
								val strokePx = 1.dp.toPx()
								val topAlpha = topDividerAlphaState.value
								if (topAlpha > 0.01f) {
									drawLine(
										color = dividerColor.copy(alpha = topAlpha),
										start = Offset(0f, strokePx / 2f),
										end = Offset(size.width, strokePx / 2f),
										strokeWidth = strokePx,
									)
								}
								val bottomAlpha = bottomDividerAlphaState.value
								if (bottomAlpha > 0.01f) {
									val y = size.height - strokePx / 2f
									drawLine(
										color = dividerColor.copy(alpha = bottomAlpha),
										start = Offset(0f, y),
										end = Offset(size.width, y),
										strokeWidth = strokePx,
									)
								}
							},
					) {
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.verticalScroll(scrollState),
						) {
							if (message != null) {
								Text(
									text = message,
									style = MaterialTheme.typography.bodyMedium,
									color = colors.onSurfaceVariant,
									modifier = Modifier.fillMaxWidth(),
								)
								if (content != null) {
									Spacer(Modifier.height(16.dp))
								}
							}
							content?.invoke(this)
						}
					}
				}

				// ── Pinned Footer ────────────────────────────────────────────────
				if (footer != null) {
					Spacer(Modifier.height(20.dp))
					footer()
				}
			}
		}
	}
}

/**
 * `46dp` circular tonal icon button (error container when [destructive]), the settings back button's
 * size, that stays round and springs bigger while pressed.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DialogCornerButton(
	action: DialogCornerAction,
	modifier: Modifier = Modifier,
	destructive: Boolean = false,
) {
	val motion = MaterialTheme.motionScheme
	val haptic = rememberHapticEffect()
	val interactionSource = remember { MutableInteractionSource() }
	val isPressed by interactionSource.collectIsPressedAsState()
	val scale by animateFloatAsState(
		targetValue = if (isPressed && action.enabled) 1.3f else 1f,
		animationSpec = motion.fastSpatialSpec(),
		label = "cornerBtnScale",
	)
	FilledTonalIconButton(
		onClick = {
			haptic(HapticEffect.CLICK)
			action.onClick()
		},
		shape = CircleShape,
		enabled = action.enabled,
		colors = if (destructive) {
			IconButtonDefaults.filledTonalIconButtonColors(
				containerColor = MaterialTheme.colorScheme.errorContainer,
				contentColor = MaterialTheme.colorScheme.onErrorContainer,
			)
		} else {
			IconButtonDefaults.filledTonalIconButtonColors()
		},
		interactionSource = interactionSource,
		modifier = modifier
			.size(CornerButtonSize)
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			},
	) {
		Icon(
			painter = action.icon,
			contentDescription = action.label,
			modifier = Modifier.size(CornerIconSize),
		)
	}
}

/**
 * Full-height `56dp` M3 Expressive pill button with spring-morphing press shapes
 * ([ButtonDefaults.shapes]), filled ([primary]), tonal, or error-tinted ([destructive]).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressivePillButton(
	text: String,
	modifier: Modifier = Modifier,
	icon: Painter? = null,
	primary: Boolean = true,
	destructive: Boolean = false,
	outlined: Boolean = false,
	enabled: Boolean = true,
	colors: ButtonColors? = null,
	onClick: () -> Unit,
) {
	val scheme = MaterialTheme.colorScheme
	val label: @Composable RowScope.() -> Unit = {
		if (icon != null) {
			Icon(painter = icon, contentDescription = null, modifier = Modifier.size(20.dp))
			Spacer(Modifier.width(8.dp))
		}
		Text(
			text = text,
			style = MaterialTheme.typography.labelLarge,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
	val btnModifier = modifier
		.fillMaxWidth()
		.height(PillButtonHeight)
	if (outlined) {
		OutlinedButton(
			onClick = onClick,
			enabled = enabled,
			shapes = ButtonDefaults.shapes(),
			colors = colors ?: ButtonDefaults.outlinedButtonColors(),
			modifier = btnModifier,
			content = label,
		)
	} else if (primary || destructive) {
		val resolvedColors = colors ?: if (destructive) {
			ButtonDefaults.buttonColors(
				containerColor = scheme.errorContainer,
				contentColor = scheme.onErrorContainer,
			)
		} else {
			ButtonDefaults.buttonColors()
		}
		Button(
			onClick = onClick,
			enabled = enabled,
			shapes = ButtonDefaults.shapes(),
			colors = resolvedColors,
			modifier = btnModifier,
			content = label,
		)
	} else {
		FilledTonalButton(
			onClick = onClick,
			enabled = enabled,
			shapes = ButtonDefaults.shapes(),
			colors = colors ?: ButtonDefaults.filledTonalButtonColors(),
			modifier = btnModifier,
			content = label,
		)
	}
}

/** One button entry rendered by [ExpressiveButtonGrid]. */
class DialogButtonSpec(
	val label: String,
	val icon: Painter? = null,
	val primary: Boolean = false,
	val destructive: Boolean = false,
	val enabled: Boolean = true,
	val onClick: () -> Unit,
)

/**
 * Lays out footer [buttons] up to 2 per row when both labels fit cleanly on a single line, and
 * stretches any lone button (or pair whose label is too wide for half a row) across the full row.
 */
@Composable
fun ExpressiveButtonGrid(
	buttons: List<DialogButtonSpec>,
	modifier: Modifier = Modifier,
) {
	if (buttons.isEmpty()) return
	if (buttons.size == 1) {
		val btn = buttons[0]
		ExpressivePillButton(
			text = btn.label,
			icon = btn.icon,
			primary = btn.primary,
			destructive = btn.destructive,
			enabled = btn.enabled,
			onClick = btn.onClick,
			modifier = modifier,
		)
		return
	}

	val textMeasurer = rememberTextMeasurer()
	val labelStyle = MaterialTheme.typography.labelLarge
	val density = LocalDensity.current

	BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
		val gap = 8.dp
		val halfButtonWidthPx = with(density) { ((maxWidth - gap) / 2).roundToPx() }
		val buttonPaddingPx = with(density) { 32.dp.roundToPx() }
		val iconExtraPx = with(density) { 28.dp.roundToPx() }

		fun fitsHalfRow(btn: DialogButtonSpec): Boolean {
			val availPx = halfButtonWidthPx - buttonPaddingPx - (if (btn.icon != null) iconExtraPx else 0)
			if (availPx <= 0) return false
			val result = textMeasurer.measure(
				text = btn.label,
				style = labelStyle,
				maxLines = 1,
				softWrap = false,
			)
			return result.size.width <= availPx
		}

		Column(
			modifier = Modifier.fillMaxWidth(),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			buttons.chunked(2).forEach { pair ->
				if (pair.size == 2 && fitsHalfRow(pair[0]) && fitsHalfRow(pair[1])) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(gap),
					) {
						pair.forEach { btn ->
							ExpressivePillButton(
								text = btn.label,
								icon = btn.icon,
								primary = btn.primary,
								destructive = btn.destructive,
								enabled = btn.enabled,
								onClick = btn.onClick,
								modifier = Modifier.weight(1f),
							)
						}
					}
				} else {
					pair.forEach { btn ->
						ExpressivePillButton(
							text = btn.label,
							icon = btn.icon,
							primary = btn.primary,
							destructive = btn.destructive,
							enabled = btn.enabled,
							onClick = btn.onClick,
						)
					}
				}
			}
		}
	}
}

/**
 * M3 Expressive segmented row for radio/checkbox/option lists inside a popup: `16dp` outer corners,
 * `4dp` inner corners, and `2dp` vertical separation; a lone option ([count] = 1) gets no container.
 * The row never highlights; callers color the
 * selected option's text instead (radio and checkbox rows alike).
 */
@Composable
fun ExpressiveOptionGroupItem(
	index: Int,
	count: Int,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	onClick: (() -> Unit)? = null,
	content: @Composable RowScope.() -> Unit,
) {
	val colors = MaterialTheme.colorScheme
	val shape = expressiveGroupItemShape(index, count)
	// A lone option isn't a group: no container, lined up with the message text instead.
	val isSingle = count == 1
	Row(
		modifier = modifier
			.padding(top = if (index > 0) 2.dp else 0.dp)
			.fillMaxWidth()
			.heightIn(min = 52.dp)
			.clip(shape)
			.then(if (isSingle) Modifier else Modifier.background(colors.background))
			.then(
				if (onClick != null) {
					Modifier.clickable(enabled = enabled, onClick = onClick)
				} else {
					Modifier
				},
			)
			.padding(horizontal = if (isSingle) 0.dp else 14.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
		content = content,
	)
}

fun expressiveGroupItemShape(index: Int, count: Int): Shape {
	val outer = 16.dp
	val inner = 4.dp
	return RoundedCornerShape(
		topStart = if (index == 0) outer else inner,
		topEnd = if (index == 0) outer else inner,
		bottomStart = if (index >= count - 1) outer else inner,
		bottomEnd = if (index >= count - 1) outer else inner,
	)
}

/** One selectable destination in [showActionChoiceDialog]. */
class DialogAction(
	val label: String,
	/** The lowest-emphasis choice, e.g. a "don't allow" under "allow always" / "allow once". */
	val outlined: Boolean = false,
	val onClick: () -> Unit,
)

/**
 * Expressive "choose a destination" popup: [icon] + [title] + a top-right copy button (or close
 * button without [onCopyTitle]), optional [message], and [actions] as full-width pills stacked like the
 * original big-buttons dialog: the first filled, the rest tonal (or outlined).
 */
fun showActionChoiceDialog(
	context: Context,
	@DrawableRes icon: Int,
	title: String,
	message: String? = null,
	actions: List<DialogAction>,
	dismissLabel: String = context.getString(R.string.close),
	onCopyTitle: (() -> Unit)? = null,
) {
	showComposeDialog(context) { dismiss ->
		val copyLabel = stringResource(androidx.preference.R.string.copy)
		ExpressiveDialogCard(
			icon = rememberAnyDrawablePainter(icon),
			title = title,
			message = message,
			// The copy button takes the cross's place: tapping outside or going back still closes it.
			cornerAction = if (onCopyTitle != null) {
				DialogCornerAction(
					icon = painterResource(R.drawable.ic_content_copy),
					label = copyLabel,
					onClick = onCopyTitle,
				)
			} else {
				DialogCornerAction(
					icon = painterResource(R.drawable.ic_close),
					label = dismissLabel,
					onClick = dismiss,
				)
			},
			footer = if (actions.isNotEmpty()) {
				{
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						actions.forEachIndexed { index, action ->
							ExpressivePillButton(
								text = action.label,
								primary = index == 0,
								outlined = action.outlined,
								onClick = {
									dismiss()
									action.onClick()
								},
							)
						}
					}
				}
			} else {
				null
			},
		)
	}
}

/**
 * Expressive multi-choice picker: tick-box rows for [options] (each trailed by its [counts] entry, if
 * given) with initial [selectedIndices], confirmed via the top-right tick button.
 */
fun showMultiChoiceDialog(
	context: Context,
	@DrawableRes icon: Int,
	title: String,
	message: String? = null,
	options: List<String>,
	counts: List<String>? = null,
	selectedIndices: Set<Int>,
	confirmLabel: String,
	isValid: (Set<Int>) -> Boolean = { true },
	onConfirm: (Set<Int>) -> Unit,
) {
	showComposeDialog(context) { dismiss ->
		var selection by remember { mutableStateOf(selectedIndices) }
		ExpressiveDialogCard(
			icon = rememberAnyDrawablePainter(icon),
			title = title,
			message = message,
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_check),
				label = confirmLabel,
				enabled = isValid(selection),
				onClick = {
					dismiss()
					onConfirm(selection)
				},
			),
		) {
			val colors = MaterialTheme.colorScheme
			options.forEachIndexed { index, option ->
				val checked = index in selection
				ExpressiveOptionGroupItem(
					index = index,
					count = options.size,
					onClick = { selection = if (checked) selection - index else selection + index },
				) {
					Checkbox(checked = checked, onCheckedChange = null)
					Spacer(Modifier.width(12.dp))
					Text(
						text = option,
						style = MaterialTheme.typography.bodyLarge,
						fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
						color = if (checked) colors.primary else colors.onSurface,
						modifier = Modifier.weight(1f),
					)
					counts?.getOrNull(index)?.let {
						Spacer(Modifier.width(12.dp))
						Text(text = it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
					}
				}
			}
		}
	}
}

/**
 * Expressive single-choice picker: segmented radio [options] with an initial [selectedIndex],
 * confirmed by a pinned bottom button ([confirmLabel], error-tinted when [destructive]) and
 * dismissed by the top-right cross button.
 */
fun showSingleChoiceDialog(
	context: Context,
	@DrawableRes icon: Int,
	title: String,
	options: List<String>,
	selectedIndex: Int,
	confirmLabel: String,
	destructive: Boolean = false,
	onConfirm: (Int) -> Unit,
) {
	showComposeDialog(context) { dismiss ->
		var selection by remember { mutableIntStateOf(selectedIndex) }
		val colors = MaterialTheme.colorScheme
		ExpressiveDialogCard(
			icon = rememberAnyDrawablePainter(icon),
			title = title,
			destructive = destructive,
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_close),
				label = stringResource(android.R.string.cancel),
				onClick = dismiss,
			),
			footer = {
				ExpressivePillButton(
					text = confirmLabel,
					primary = true,
					destructive = destructive,
					onClick = {
						dismiss()
						onConfirm(selection)
					},
				)
			},
		) {
			options.forEachIndexed { index, option ->
				val isSelected = index == selection
				ExpressiveOptionGroupItem(
					index = index,
					count = options.size,
					onClick = { selection = index },
				) {
					RadioButton(selected = isSelected, onClick = null)
					Spacer(Modifier.width(12.dp))
					Text(
						text = option,
						style = MaterialTheme.typography.bodyLarge,
						fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
						color = if (isSelected) colors.primary else colors.onSurface,
						modifier = Modifier.weight(1f),
					)
				}
			}
		}
	}
}

/**
 * Expressive confirm / info popup for View code: [icon] + [title] + [message], a top-right cross, and a
 * pinned footer with [confirmLabel] (error-tinted when [destructive]) plus an optional
 * [secondaryLabel] button (the old neutral button). Non-[cancelable] popups close only via a button.
 */
fun showConfirmDialog(
	context: Context,
	@DrawableRes icon: Int,
	title: String,
	message: String? = null,
	confirmLabel: String = context.getString(android.R.string.ok),
	destructive: Boolean = false,
	secondaryLabel: String? = null,
	onSecondary: (() -> Unit)? = null,
	cancelable: Boolean = true,
	onCancel: (() -> Unit)? = null,
	onConfirm: () -> Unit,
): ComponentDialog = showComposeDialog(context, cancelable = cancelable, onCancel = onCancel) { dismiss ->
	ExpressiveDialogCard(
		icon = rememberAnyDrawablePainter(icon),
		title = title,
		message = message,
		destructive = destructive,
		cornerAction = DialogCornerAction(
			icon = painterResource(R.drawable.ic_close),
			label = stringResource(android.R.string.cancel),
			onClick = {
				dismiss()
				onCancel?.invoke()
			},
		),
		footer = {
			ExpressiveButtonGrid(
				buttons = listOfNotNull(
					DialogButtonSpec(
						label = confirmLabel,
						primary = true,
						destructive = destructive,
						onClick = {
							dismiss()
							onConfirm()
						},
					),
					secondaryLabel?.let {
						DialogButtonSpec(
							label = it,
							onClick = {
								dismiss()
								onSecondary?.invoke()
							},
						)
					},
				),
			)
		},
	)
}

/**
 * Expressive text input popup for View code: [icon] + [title], optional [message], a single-line field
 * prefilled with [initialValue], and a top-right tick that submits (also the keyboard's Done key).
 * The tick stays disabled while [isValid] rejects the text.
 */
fun showTextInputDialog(
	context: Context,
	@DrawableRes icon: Int,
	title: String,
	message: String? = null,
	initialValue: String = "",
	hint: String? = null,
	keyboardType: KeyboardType = KeyboardType.Text,
	isValid: (String) -> Boolean = { it.isNotBlank() },
	onConfirm: (String) -> Unit,
): ComponentDialog = showComposeDialog(context) { dismiss ->
	var value by remember { mutableStateOf(initialValue) }
	val submit = {
		if (isValid(value)) {
			dismiss()
			onConfirm(value)
		}
	}
	ExpressiveDialogCard(
		icon = rememberAnyDrawablePainter(icon),
		title = title,
		message = message,
		cornerAction = DialogCornerAction(
			icon = painterResource(R.drawable.ic_check),
			label = stringResource(android.R.string.ok),
			enabled = isValid(value),
			onClick = submit,
		),
	) {
		OutlinedTextField(
			value = value,
			onValueChange = { value = it },
			placeholder = hint?.let { { Text(it) } },
			singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
			keyboardActions = KeyboardActions(onDone = { submit() }),
			shape = RoundedCornerShape(16.dp),
			modifier = Modifier.fillMaxWidth(),
		)
	}
}
