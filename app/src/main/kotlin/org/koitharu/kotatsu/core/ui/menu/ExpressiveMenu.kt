package org.koitharu.kotatsu.core.ui.menu

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.MenuRes
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.google.android.material.R as materialR

/** Drop-in for appcompat's PopupMenu that shows the app's Compose menu instead. */
@SuppressLint("RestrictedApi")
class MenuPopup(private val anchor: View) {

	private val builder = MenuBuilder(anchor.context)
	private var onItemClick: ((MenuItem) -> Boolean)? = null
	private var onDismiss: (() -> Unit)? = null
	private var forceShowIcon = false

	val menu: Menu get() = builder

	val menuInflater: MenuInflater get() = SupportMenuInflater(anchor.context)

	init {
		builder.setCallback(object : MenuBuilder.Callback {
			override fun onMenuItemSelected(menu: MenuBuilder, item: MenuItem) = onItemClick?.invoke(item) == true

			override fun onMenuModeChange(menu: MenuBuilder) = Unit
		})
	}

	fun inflate(@MenuRes menuRes: Int) = menuInflater.inflate(menuRes, builder)

	fun setForceShowIcon(forceShow: Boolean) {
		forceShowIcon = forceShow
	}

	fun setOnMenuItemClickListener(listener: (MenuItem) -> Boolean) {
		onItemClick = listener
	}

	fun setOnDismissListener(listener: () -> Unit) {
		onDismiss = listener
	}

	fun show() {
		showExpressiveMenu(anchor, builder, builder.visibleItems, forceShowIcon) { onDismiss?.invoke() }
	}
}

/**
 * Shows [items] of [menu] as a menu that blooms out of [anchor] and folds back into it. Picking an
 * item dispatches it through [menu], exactly like the platform popup did.
 */
@SuppressLint("RestrictedApi")
fun showExpressiveMenu(
	anchor: View,
	menu: MenuBuilder,
	items: List<MenuItemImpl>,
	forceShowIcon: Boolean = false,
	onDismiss: () -> Unit = {},
) {
	val root = anchor.rootView as? ViewGroup ?: return
	if (items.isEmpty() || !anchor.isAttachedToWindow) return
	val location = IntArray(2).also { anchor.getLocationInWindow(it) }
	val bounds = Rect(location[0], location[1], location[0] + anchor.width, location[1] + anchor.height)
	val safeArea = ViewCompat.getRootWindowInsets(anchor)?.getInsets(
		WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
	)
	val context = anchor.context
	val colors = MenuColors(
		content = Color(context.getThemeColor(R.attr.colorTopBarIcon)),
		surface = Color(context.getThemeColor(materialR.attr.colorSurfaceContainer)),
		// @color/selector_overlay, the old row selector.
		highlight = Color(ColorUtils.setAlphaComponent(context.getThemeColor(materialR.attr.colorOnSurface), 69)),
	)
	// Menus that ask for group dividers (MenuCompat.setGroupDividerEnabled) get a line where the group changes.
	val groupDividers = menu.isGroupDividerEnabled
	val entries = items.mapIndexed { index, item ->
		MenuEntry(
			item = item,
			title = item.title?.toString().orEmpty(),
			// Tinted in place, like the old row's ImageView did.
			icon = item.icon?.takeIf { forceShowIcon || item.shouldShowIcon() }
				?.mutate()?.apply { setTint(colors.content.toArgb()) }?.let(::DrawablePainter),
			// A forced icon column keeps its space on rows without an icon, like the old rows.
			reservesIconSpace = forceShowIcon,
			isEnabled = item.isEnabled,
			check = when {
				!item.isCheckable -> CheckMark.NONE
				item.isExclusiveCheckable -> CheckMark.RADIO
				else -> CheckMark.BOX
			},
			isChecked = item.isChecked,
			hasDividerAbove = groupDividers && index > 0 && items[index - 1].groupId != item.groupId,
		)
	}
	val overlay = ComposeView(context)
	overlay.setContent {
		DropSauceTheme {
			ExpressiveMenuOverlay(
				entries = entries,
				anchor = bounds,
				safeArea = safeArea?.let { Rect(it.left, it.top, it.right, it.bottom) } ?: Rect(),
				colors = colors,
				// The menu is closed on dismiss instead, so tapping outside notifies its owner too, like before.
				onItemClick = { menu.performItemAction(it, MenuBuilder.FLAG_PERFORM_NO_CLOSE) },
				onDismiss = {
					menu.close()
					onDismiss()
				},
				onClosed = { wasKeyboard ->
					overlay.post {
						root.removeView(overlay)
						if (wasKeyboard && anchor.isAttachedToWindow) anchor.requestFocus()
					}
				},
			)
		}
	}
	root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
}

private class MenuColors(val content: Color, val surface: Color, val highlight: Color)

private enum class CheckMark { NONE, BOX, RADIO }

private class MenuEntry(
	val item: MenuItem,
	val title: String,
	val icon: Painter?,
	val reservesIconSpace: Boolean,
	val isEnabled: Boolean,
	val check: CheckMark,
	val isChecked: Boolean,
	val hasDividerAbove: Boolean,
)

/** Where the card ended up, decided once at layout time; the card never moves or resizes after that. */
private class MenuGeometry(
	val origin: Offset, // centre of the dot the card blooms from, inside the card
	val startRadius: Float,
	val cardSize: Size,
	val growsDown: Boolean,
) {

	fun revealedTop(heightProgress: Float) = lerp(origin.y - startRadius, 0f, heightProgress)

	fun revealedBottom(heightProgress: Float) = lerp(origin.y + startRadius, cardSize.height, heightProgress)
}

private val CardCorner = 24.dp
// The press highlight sits CardPadding in from every card edge; content is 16dp from the sides.
private val CardPadding = 4.dp
private val ItemHeight = 44.dp
private val ItemPadding = 12.dp
private val ScreenMargin = 8.dp
private val SmallAnchor = 64.dp

// Expressive spatial springs (fast / default / slow stiffness) made critically damped: they keep the
// physical, decelerating feel but can never overshoot, so nothing swells or shrinks back at the end.
private fun <T> openWidthSpec() = spring<T>(dampingRatio = 1f, stiffness = 800f)
private fun <T> openHeightSpec() = spring<T>(dampingRatio = 1f, stiffness = 380f)
private fun <T> rowSpec() = spring<T>(dampingRatio = 1f, stiffness = 600f)
private fun <T> closeHeightSpec() = spring<T>(dampingRatio = 1f, stiffness = 1400f)
private fun <T> closeWidthSpec() = spring<T>(dampingRatio = 1f, stiffness = 900f)
private const val ICON_LEAD_MS = 30L
private const val ROW_REVEAL_SHARE = 0.4f // of a row's height the edge must pass before it cascades in

@Composable
private fun ExpressiveMenuOverlay(
	entries: List<MenuEntry>,
	anchor: Rect,
	safeArea: Rect,
	colors: MenuColors,
	onItemClick: (MenuItem) -> Unit,
	onDismiss: () -> Unit,
	onClosed: (wasKeyboard: Boolean) -> Unit,
) {
	val scope = rememberCoroutineScope()
	val motion = MaterialTheme.motionScheme
	val isKeyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
	var geometry by remember { mutableStateOf<MenuGeometry?>(null) }
	var isClosing by remember { mutableStateOf(false) }
	// Width leads and height trails: the dot stretches into a pill, then the pill unrolls into the card.
	val widthProgress = remember { Animatable(0f) }
	val heightProgress = remember { Animatable(0f) }
	val surfaceAlpha = remember { Animatable(0f) }
	val contentAlpha = remember { Animatable(1f) }

	fun close() {
		if (isClosing) return
		isClosing = true
		onDismiss()
		scope.launch {
			launch { contentAlpha.animateTo(0f, motion.fastEffectsSpec()) }
			launch { widthProgress.animateTo(0f, closeWidthSpec()) }
			launch {
				delay(110)
				surfaceAlpha.animateTo(0f, tween(110))
			}
			heightProgress.animateTo(0f, closeHeightSpec())
			onClosed(isKeyboard)
		}
	}

	BackHandler(enabled = !isClosing) { close() }

	val isPlaced = geometry != null
	LaunchedEffect(isPlaced) {
		if (!isPlaced) return@LaunchedEffect
		launch { surfaceAlpha.animateTo(1f, motion.fastEffectsSpec()) }
		launch { widthProgress.animateTo(1f, openWidthSpec()) }
		heightProgress.animateTo(1f, openHeightSpec())
	}

	Box(Modifier.fillMaxSize()) {
		// Any touch outside the card closes the menu, like the platform popup.
		Spacer(
			Modifier
				.fillMaxSize()
				.pointerInput(Unit) {
					awaitEachGesture {
						awaitFirstDown()
						close()
					}
				},
		)
		Layout(
			content = {
				MenuCard(
					entries = entries,
					colors = colors,
					geometry = { geometry },
					widthProgress = { widthProgress.value },
					heightProgress = { heightProgress.value },
					surfaceAlpha = { surfaceAlpha.value },
					contentAlpha = { contentAlpha.value },
					requestInitialFocus = isKeyboard,
					onItemClick = { item ->
						if (!isClosing) {
							onItemClick(item)
							close()
						}
					},
				)
			},
		) { measurables, constraints ->
			val margin = ScreenMargin.roundToPx()
			val left = safeArea.left + margin
			val top = safeArea.top + margin
			val right = constraints.maxWidth - safeArea.right - margin
			val bottom = constraints.maxHeight - safeArea.bottom - margin
			val card = measurables.single().measure(
				Constraints(maxWidth = max(right - left, 0), maxHeight = max(bottom - top, 0)),
			)
			// The card's corner sits on the anchor's matching corner, so it covers the button it grows from.
			val alignEnd = anchor.centerX() > constraints.maxWidth / 2
			val x = (if (alignEnd) anchor.right - card.width else anchor.left)
				.coerceIn(left, max(right - card.width, left))
			val down = anchor.top + card.height <= bottom || bottom - anchor.top >= anchor.bottom - top
			val y = (if (down) anchor.top else anchor.bottom - card.height)
				.coerceIn(top, max(bottom - card.height, top))
			if (geometry == null) {
				val isButton = anchor.width() <= SmallAnchor.toPx() && anchor.height() <= SmallAnchor.toPx()
				val radius = if (isButton) {
					(min(anchor.width(), anchor.height()) / 2f).coerceIn(12.dp.toPx(), CardCorner.toPx())
				} else {
					20.dp.toPx()
				}
				// A button blooms from its own centre; a long-pressed row or chip from the card's nearest corner.
				val originX = if (isButton) anchor.exactCenterX() - x else if (alignEnd) card.width - radius else radius
				val originY = if (isButton) anchor.exactCenterY() - y else if (down) radius else card.height - radius
				geometry = MenuGeometry(
					origin = Offset(
						originX.coerceIn(radius, max(card.width - radius, radius)),
						originY.coerceIn(radius, max(card.height - radius, radius)),
					),
					startRadius = radius,
					cardSize = Size(card.width.toFloat(), card.height.toFloat()),
					growsDown = down,
				)
			}
			layout(constraints.maxWidth, constraints.maxHeight) {
				card.place(x, y)
			}
		}
	}
}

@Composable
private fun MenuCard(
	entries: List<MenuEntry>,
	colors: MenuColors,
	geometry: () -> MenuGeometry?,
	widthProgress: () -> Float,
	heightProgress: () -> Float,
	surfaceAlpha: () -> Float,
	contentAlpha: () -> Float,
	requestInitialFocus: Boolean,
	onItemClick: (MenuItem) -> Unit,
) {
	var cardCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
	val focusRequester = remember { FocusRequester() }
	val firstFocusable = entries.indexOfFirst { it.isEnabled }
	// The old rows' text resolved to the theme's textAppearanceBodyLarge (gflex_body 16sp), same as this.
	val textStyle = MaterialTheme.typography.bodyLarge
	Box(
		Modifier
			.onPlaced { cardCoordinates = it }
			.graphicsLayer {
				val g = geometry()
				if (g == null) {
					alpha = 0f
					return@graphicsLayer
				}
				shape = BloomShape(g, widthProgress(), heightProgress(), CardCorner.toPx())
				clip = true
				shadowElevation = 3.dp.toPx()
				alpha = surfaceAlpha()
			}
			.background(colors.surface)
			.pointerInput(Unit) {}, // taps on the padding must not reach the dismiss layer
	) {
		Column(
			modifier = Modifier
				.graphicsLayer { alpha = contentAlpha() }
				.widthIn(min = 160.dp, max = 320.dp)
				.width(IntrinsicSize.Max)
				.verticalScroll(rememberScrollState())
				.padding(CardPadding),
		) {
			entries.forEachIndexed { index, entry ->
				if (entry.hasDividerAbove) {
					Box(
						Modifier
							.padding(horizontal = ItemPadding, vertical = 4.dp)
							.fillMaxWidth()
							.height(1.dp)
							.background(colors.content.copy(alpha = 0.12f)),
					)
				}
				MenuRow(
					entry = entry,
					colors = colors,
					textStyle = textStyle,
					cardCoordinates = { cardCoordinates },
					geometry = geometry,
					heightProgress = heightProgress,
					modifier = if (index == firstFocusable) Modifier.focusRequester(focusRequester) else Modifier,
					onClick = { onItemClick(entry.item) },
				)
			}
		}
	}
	if (requestInitialFocus && firstFocusable >= 0) {
		LaunchedEffect(Unit) { focusRequester.requestFocus() }
	}
}

@Composable
private fun MenuRow(
	entry: MenuEntry,
	colors: MenuColors,
	textStyle: TextStyle,
	cardCoordinates: () -> LayoutCoordinates?,
	geometry: () -> MenuGeometry?,
	heightProgress: () -> Float,
	modifier: Modifier,
	onClick: () -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val isPressed by interactionSource.collectIsPressedAsState()
	val isFocused by interactionSource.collectIsFocusedAsState()
	var rowTop by remember { mutableFloatStateOf(Float.NaN) }
	var rowHeight by remember { mutableFloatStateOf(0f) }
	val iconProgress = remember { Animatable(0f) }
	val textProgress = remember { Animatable(0f) }
	// Rows cascade in one after another as the unrolling edge sweeps over them, icon slightly first.
	LaunchedEffect(Unit) {
		snapshotFlow {
			val g = geometry() ?: return@snapshotFlow false
			val h = heightProgress()
			if (rowTop.isNaN()) return@snapshotFlow false
			// Rows scrolled past the card's end still appear once the card is fully open.
			h >= 0.98f || if (g.growsDown) {
				g.revealedBottom(h) >= rowTop + rowHeight * ROW_REVEAL_SHARE
			} else {
				g.revealedTop(h) <= rowTop + rowHeight * (1f - ROW_REVEAL_SHARE)
			}
		}.first { it }
		launch { iconProgress.animateTo(1f, rowSpec()) }
		delay(ICON_LEAD_MS)
		textProgress.animateTo(1f, rowSpec())
	}

	val slideDirection = { if (geometry()?.growsDown != false) -1f else 1f }
	Row(
		modifier = modifier
			.onPlaced { coords ->
				rowHeight = coords.size.height.toFloat()
				cardCoordinates()?.let { rowTop = it.localPositionOf(coords, Offset.Zero).y }
			}
			.fillMaxWidth()
			.height(ItemHeight)
			.clip(RoundedCornerShape(ItemHeight / 2))
			.background(if (isPressed || isFocused) colors.highlight else Color.Transparent)
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				enabled = entry.isEnabled,
				role = Role.Button,
				onClick = onClick,
			)
			.padding(horizontal = ItemPadding),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (entry.icon != null || entry.reservesIconSpace) {
			Box(
				Modifier
					.size(22.dp)
					.graphicsLayer {
						val p = iconProgress.value
						alpha = p.coerceIn(0f, 1f)
						scaleX = lerp(0.5f, 1f, p)
						scaleY = scaleX
					},
			) {
				entry.icon?.let { Image(painter = it, contentDescription = null, contentScale = ContentScale.Inside) }
			}
			Spacer(Modifier.width(ItemPadding))
		}
		Row(
			modifier = Modifier
				.weight(1f)
				.graphicsLayer {
					val p = textProgress.value
					alpha = (p * 1.4f).coerceIn(0f, 1f)
					translationY = (1f - p) * 6.dp.toPx() * slideDirection()
				},
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = entry.title,
				style = textStyle,
				color = colors.content,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			if (entry.check != CheckMark.NONE) Spacer(Modifier.width(ItemPadding))
			when (entry.check) {
				CheckMark.NONE -> Unit
				CheckMark.BOX -> Checkbox(
					checked = entry.isChecked,
					onCheckedChange = null,
					colors = CheckboxDefaults.colors(
						checkedColor = colors.content,
						uncheckedColor = colors.content,
						checkmarkColor = colors.surface,
					),
				)

				CheckMark.RADIO -> RadioButton(
					selected = entry.isChecked,
					onClick = null,
					colors = RadioButtonDefaults.colors(selectedColor = colors.content, unselectedColor = colors.content),
				)
			}
		}
	}
}

/** Draws a menu item's drawable as-is, the way the old row's ImageView did. */
private class DrawablePainter(private val drawable: Drawable) : Painter() {

	override val intrinsicSize: Size
		get() = Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())

	override fun DrawScope.onDraw() {
		drawIntoCanvas {
			drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
			drawable.draw(it.nativeCanvas)
		}
	}
}

/** The card's visible part: a round dot on the anchor that stretches into a pill, then unrolls into the card. */
private class BloomShape(
	private val geometry: MenuGeometry,
	private val widthProgress: Float,
	private val heightProgress: Float,
	private val cornerRadius: Float,
) : Shape {

	override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
		val w = widthProgress.coerceIn(0f, 1f)
		val h = heightProgress.coerceIn(0f, 1f)
		val r = geometry.startRadius
		val left = lerp(geometry.origin.x - r, 0f, w)
		val right = lerp(geometry.origin.x + r, size.width, w)
		val top = geometry.revealedTop(h)
		val bottom = geometry.revealedBottom(h)
		// Fully round while it is a dot or a pill, settling into the card's corners as it unrolls.
		val corner = min(lerp(r, cornerRadius, h), min(right - left, bottom - top) / 2f)
		return Outline.Rounded(RoundRect(left, top, right, bottom, CornerRadius(corner)))
	}
}
