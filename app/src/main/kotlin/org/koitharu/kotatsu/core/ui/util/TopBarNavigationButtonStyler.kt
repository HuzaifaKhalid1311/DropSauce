package org.koitharu.kotatsu.core.ui.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import androidx.core.view.doOnPreDraw
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import com.google.android.material.R as materialR
import kotlin.math.roundToInt

/**
 * The app's unified top-bar treatment, applied to every [Toolbar] (and the contextual action-mode
 * bar). It enforces a single design language for the bar's clickable icons:
 *
 *  - A circular tonal **navigation button** (back / close) pinned to the start edge.
 *  - A **connected button group** on the end edge that holds the action icons, mirroring the group
 *    used on each row of the extension store. Each action is its own tonal segment, as tall as the
 *    navigation button but [TonalBarMetrics.segmentWidth] wide so several of them together stay
 *    within the footprint of the single pill they replaced; segments are separated by
 *    [TonalBarMetrics.segmentSpacing] and share [TonalBarMetrics.innerCornerRadius] on the abutting
 *    edges, while the group's outer edges stay fully rounded. A lone action widens back to the
 *    navigation button's diameter, so it renders as an identical circle.
 *
 * Every icon — back, close, each action, the overflow "more" button — is rendered identically:
 * forced to [TonalBarMetrics.iconSize], centered on both axes inside its segment with a ripple
 * bounded to that shape, and tinted with `colorTopBarIcon` (normally the reference group's
 * `colorOnSecondaryContainer`) over the unchanged `colorSurfaceContainer` fill. The group's end
 * inset mirrors the navigation button's start margin, so the bar is symmetric edge-to-edge.
 *
 * Menus inflate (and re-bind) asynchronously, so the styling re-applies on every layout pass. It is
 * idempotent — it only writes a property when the current value differs — so it converges without
 * triggering relayout loops.
 */
fun Toolbar.applyTonalTopBarStyle() {
	applyTonalNavigationButtonStyle()
	applyTonalActionMenuStyle()
}

/** Resolved, screen-density-correct measurements shared by every part of the treatment. */
private class TonalBarMetrics(context: Context) {
	val cellSize = context.dimen(R.dimen.top_bar_navigation_button_size)
	val iconSize = context.dimen(R.dimen.top_bar_action_icon_size)
	val edgeMargin = context.dimen(R.dimen.top_bar_navigation_button_margin_start)

	/**
	 * Segments are narrower than they are tall: the group holds several of them, so trimming the
	 * width keeps the whole group no wider than the single pill it replaced. The height stays
	 * [cellSize] so the group lines up with the circular navigation button.
	 */
	val segmentWidth = context.dimen(R.dimen.top_bar_action_segment_width)
	val segmentSpacing = context.dimen(R.dimen.top_bar_action_segment_spacing)
	val innerCornerRadius = context.dimen(R.dimen.top_bar_action_segment_inner_corner).toFloat()
	val outerCornerRadius = cellSize / 2f

	/** Symmetric padding that centers an [iconSize] icon inside the square navigation button. */
	val iconInset = ((cellSize - iconSize) / 2).coerceAtLeast(0)

	/** The same, for the narrower action segment — horizontal and vertical differ there. */
	val segmentIconInsetX = ((segmentWidth - iconSize) / 2).coerceAtLeast(0)
	val segmentIconInsetY = iconInset
	val iconTintColor = context.getThemeColor(R.attr.colorTopBarIcon)
	val iconTint: ColorStateList = ColorStateList.valueOf(iconTintColor)
}

fun Toolbar.applyTonalNavigationButtonStyle() {
	// Set the title inset synchronously — it doesn't depend on the navigation button view existing
	// yet — so the title never visibly slides when the toolbar is first laid out. This is what
	// caused the back-arrow + title to stutter on an in-place activity recreate (e.g. after a
	// colour-scheme change), where there is no enter transition to mask a post-layout reflow.
	contentInsetStartWithNavigation = resources.getDimensionPixelSize(R.dimen.top_bar_title_inset_with_navigation)
	// Expanding an in-toolbar action view (the inline search field) swaps the button in this slot for
	// a different view — see [findNavigationButton] — so re-style on every layout pass rather than
	// once, the same way the action menu does. Styling is idempotent, so repeating it is free.
	if (getTag(R.id.tag_tonal_navigation_button) == null) {
		setTag(R.id.tag_tonal_navigation_button, true)
		addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
			(view as? Toolbar)?.applyTonalNavigationButtonStyleNow()
		}
	}
	// The navigation button is created lazily once a navigation icon is set, so style it just before
	// the next draw rather than via post() (which runs after the first frame and pops in visibly).
	doOnPreDraw { applyTonalNavigationButtonStyleNow() }
}

private fun Toolbar.applyTonalNavigationButtonStyleNow() {
	val navigationButton = findNavigationButton() ?: return
	val metrics = TonalBarMetrics(context)
	navigationButton.updateLayoutSize(
		width = metrics.cellSize,
		height = metrics.cellSize,
		marginStart = metrics.edgeMargin,
		gravity = Gravity.START or Gravity.CENTER_VERTICAL,
	)
	navigationButton.applyTonalCircleButton(metrics)
}

/**
 * Styles a standalone icon button (e.g. the contextual action-mode close "X") to match the circular
 * tonal navigation button: a solid circular fill, a centered [TonalBarMetrics.iconSize] icon, and a
 * centered circular ripple.
 */
fun ImageView.applyTonalIconButtonStyle() {
	val metrics = TonalBarMetrics(context)
	updateLayoutSize(metrics.cellSize, metrics.cellSize, marginStart = metrics.edgeMargin)
	applyTonalCircleButton(metrics)
}

/**
 * Groups the action (menu) icons of a top bar into a single tonal pill (see [applyTonalTopBarStyle]).
 * Works for both a [Toolbar] and the contextual action-mode bar (`ActionBarContextView`), since both
 * host their items inside an [ActionMenuView].
 */
fun ViewGroup.applyTonalActionMenuStyle() {
	if (getTag(R.id.tag_tonal_action_menu) == null) {
		setTag(R.id.tag_tonal_action_menu, true)
		addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
			(view as? ViewGroup)?.applyTonalActionMenuStyleNow()
		}
	}
	applyTonalActionMenuStyleNow()
	doOnPreDraw { applyTonalActionMenuStyleNow() }
}

internal fun ViewGroup.applyTonalActionMenuStyleNow() {
	findActionMenuView()?.applyTonalPillStyle()
}

private fun ActionMenuView.applyTonalPillStyle() {
	val metrics = TonalBarMetrics(context)

	// One-time container setup: the group itself draws nothing — each segment carries its own tonal
	// fill — so it only needs zero padding and vertical-center gravity, which keeps the segments
	// centered no matter how tall the container is ultimately measured.
	if (getTag(R.id.tag_tonal_action_pill) == null) {
		setTag(R.id.tag_tonal_action_pill, true)
		setPadding(0, 0, 0, 0)
		background = null
		clipToOutline = false
		gravity = Gravity.CENTER_VERTICAL
		// The gap between segments has to be a divider, not a child margin: ActionMenuView.onMeasure
		// resets every child's left/right margin to 0 whenever it is measured with a non-EXACTLY width
		// spec — which is always the case inside a Toolbar — so margins are wiped on each pass and the
		// segments end up touching. The LinearLayoutCompat divider it inherits is untouched by that.
		dividerDrawable = spacerDrawable(metrics.segmentSpacing)
		showDividers = LinearLayoutCompat.SHOW_DIVIDER_MIDDLE
		dividerPadding = 0
	}

	// Pin the container to exactly the navigation-button height, center it vertically, push it to the
	// end edge and mirror the navigation button's start margin on the end side. The default container
	// fills the whole bar height (MATCH_PARENT) — that is what made the pill too tall before.
	updateLayoutSize(
		width = layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
		height = metrics.cellSize,
		marginEnd = metrics.edgeMargin,
		gravity = Gravity.END or Gravity.CENTER_VERTICAL,
	)

	// Only the icon-only items form the connected group, so first/last are computed over those alone.
	val segments = children.filter { it.isPillActionItem() }.toList()
	val isRtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
	segments.forEachIndexed { index, child ->
		val isFirst = index == 0
		val isLast = index == segments.lastIndex
		child.applyTonalActionCell(
			metrics = metrics,
			roundLeft = if (isRtl) isLast else isFirst,
			roundRight = if (isRtl) isFirst else isLast,
		)
	}
}

/** Only icon-only items belong in the pill; text actions keep their default (wider) layout. */
private fun View.isPillActionItem(): Boolean = when (this) {
	is ImageView -> true // overflow button & image action views
	is TextView -> text.isNullOrEmpty() // icon-only ActionMenuItemView (a TextView subclass)
	else -> false
}

/**
 * Lays out one action as a segment of the connected group: its own tonal fill with the
 * group's outer corners fully rounded and the abutting ones small, a ripple bounded to that shape,
 * and a centered, uniformly-sized icon.
 */
private fun View.applyTonalActionCell(
	metrics: TonalBarMetrics,
	roundLeft: Boolean,
	roundRight: Boolean,
) {
	if (getTag(R.id.tag_tonal_action_item) == null) {
		setTag(R.id.tag_tonal_action_item, true)
		minimumWidth = 0
		minimumHeight = 0
	}
	// The shape depends on the segment's position, which changes whenever the menu is rebound, so
	// rebuild the background only when that position actually changed.
	val shapeKey = (if (roundLeft) 1 else 0) or (if (roundRight) 2 else 0)
	if (getTag(R.id.tag_tonal_action_shape) != shapeKey) {
		setTag(R.id.tag_tonal_action_shape, shapeKey)
		background = context.createSegmentBackground(metrics, roundLeft, roundRight)
	}
	// A lone action has both outer edges rounded, so give it the navigation button's full width: it
	// then renders as an identical circle instead of a slightly squashed stadium.
	val isOnlySegment = roundLeft && roundRight
	val width = if (isOnlySegment) metrics.cellSize else metrics.segmentWidth
	val insetX = if (isOnlySegment) metrics.iconInset else metrics.segmentIconInsetX
	updateLayoutSize(width, metrics.cellSize)
	when (this) {
		// Overflow button & image action views: scale the drawable into the centered icon box.
		is ImageView -> {
			if (scaleType != ImageView.ScaleType.FIT_CENTER) {
				scaleType = ImageView.ScaleType.FIT_CENTER
			}
			updatePaddingTo(insetX, metrics.segmentIconInsetY)
			forceImageIconTint(metrics)
		}
		// Icon-only ActionMenuItemView: the icon is a *left compound drawable*, which a TextView always
		// draws at paddingLeft (gravity centers the text, not the drawable). So symmetric padding —
		// not gravity — is what actually centers the icon in the cell.
		is TextView -> {
			updatePaddingTo(insetX, metrics.segmentIconInsetY)
			if (gravity != Gravity.CENTER) {
				gravity = Gravity.CENTER
			}
			if (compoundDrawablePadding != 0) {
				compoundDrawablePadding = 0
			}
			forceCompoundIcon(metrics)
		}
	}
}

/**
 * Forces an [ActionMenuItemView]'s icon to [TonalBarMetrics.iconSize] and the shared tint so every
 * icon matches the navigation button regardless of its drawable's intrinsic size. Re-applied only
 * when the drawable instance changes (tracked via a tag) so steady-state layout passes don't loop.
 */
private fun TextView.forceCompoundIcon(metrics: TonalBarMetrics) {
	var icon = compoundDrawables.firstOrNull { it != null } ?: return
	// Re-size only when the drawable instance changes: setCompoundDrawables requests layout, so
	// guarding here keeps steady-state passes from looping. setCompoundDrawables (not the
	// …WithIntrinsicBounds variant) preserves the explicit icon bounds we set.
	var iconChanged = false
	if (getTag(R.id.tag_tonal_action_icon) !== icon) {
		icon = icon.mutate().apply { setBounds(0, 0, metrics.iconSize, metrics.iconSize) }
		setCompoundDrawables(icon, null, null, null)
		setTag(R.id.tag_tonal_action_icon, icon)
		iconChanged = true
	}
	// Re-assert the tint so every icon matches the back button. Some bars replace or rebind drawables
	// after we style them, so guarding on instance identity alone would let a later default tint win.
	// Guarding by colour keeps the action-mode pre-draw loop from invalidating the bar when the
	// drawable is already correct.
	if (iconChanged || getTag(R.id.tag_tonal_action_tint_color) != metrics.iconTintColor) {
		icon.setTintList(metrics.iconTint)
		setTag(R.id.tag_tonal_action_tint_color, metrics.iconTintColor)
	}
}

/**
 * Shared treatment for a standalone circular icon button (navigation / close): a solid circular tonal
 * fill, a centered [TonalBarMetrics.iconSize] icon and a centered circular ripple. Idempotent so it
 * can run on every layout/style pass without churn.
 */
private fun ImageView.applyTonalCircleButton(metrics: TonalBarMetrics) {
	minimumWidth = 0
	minimumHeight = 0
	if (scaleType != ImageView.ScaleType.FIT_CENTER) {
		scaleType = ImageView.ScaleType.FIT_CENTER
	}
	updatePaddingTo(metrics.iconInset)
	if (getTag(R.id.tag_tonal_action_item) == null) {
		setTag(R.id.tag_tonal_action_item, true)
		background = context.createCircleButtonBackground()
	}
	forceImageIconTint(metrics)
}

private fun ImageView.forceImageIconTint(metrics: TonalBarMetrics) {
	val icon = drawable
	val iconChanged = getTag(R.id.tag_tonal_action_icon) !== icon
	val needsTint = iconChanged ||
		getTag(R.id.tag_tonal_action_tint_color) != metrics.iconTintColor ||
		imageTintList?.defaultColor != metrics.iconTintColor ||
		colorFilter != null
	if (!needsTint) {
		return
	}
	clearColorFilter()
	imageTintList = metrics.iconTint
	icon?.mutate()?.setTintList(metrics.iconTint)
	setTag(R.id.tag_tonal_action_icon, icon)
	setTag(R.id.tag_tonal_action_tint_color, metrics.iconTintColor)
}

// region small idempotent view helpers

private fun View.updatePaddingTo(horizontal: Int, vertical: Int = horizontal) {
	if (paddingLeft != horizontal || paddingTop != vertical ||
		paddingRight != horizontal || paddingBottom != vertical
	) {
		setPadding(horizontal, vertical, horizontal, vertical)
	}
}

/** Sets size / margins / gravity on the existing layout params only when something actually changes. */
private fun View.updateLayoutSize(
	width: Int,
	height: Int,
	marginStart: Int = Int.MIN_VALUE,
	marginEnd: Int = Int.MIN_VALUE,
	gravity: Int = Int.MIN_VALUE,
) {
	val lp = layoutParams ?: return
	var changed = false
	if (lp.width != width) {
		lp.width = width
		changed = true
	}
	if (lp.height != height) {
		lp.height = height
		changed = true
	}
	if (lp is ViewGroup.MarginLayoutParams) {
		if (marginStart != Int.MIN_VALUE && lp.marginStart != marginStart) {
			lp.marginStart = marginStart
			changed = true
		}
		if (marginEnd != Int.MIN_VALUE && lp.marginEnd != marginEnd) {
			lp.marginEnd = marginEnd
			changed = true
		}
	}
	if (gravity != Int.MIN_VALUE && lp is Toolbar.LayoutParams && lp.gravity != gravity) {
		lp.gravity = gravity
		changed = true
	}
	if (changed) {
		layoutParams = lp
	}
}

// endregion

private fun Context.dimen(resId: Int) = resources.getDimensionPixelSize(resId)

private fun ViewGroup.findActionMenuView(): ActionMenuView? =
	children.filterIsInstance<ActionMenuView>().firstOrNull()

/**
 * The button occupying the toolbar's start slot. Normally that is the navigation button, but while an
 * action view is expanded (the inline search field) the toolbar parks the navigation button off-screen
 * and puts its own *collapse* button — a separate view with default appcompat geometry and no start
 * margin — in the same place. Both are the toolbar's first [ImageButton] child, so returning whichever
 * is currently there keeps the arrow from jumping when search opens.
 */
private fun Toolbar.findNavigationButton(): ImageButton? {
	if (navigationIcon == null && !hasExpandedActionView()) return null
	return children.filterIsInstance<ImageButton>().firstOrNull()
}

// region drawables

/**
 * Opacity of the back-button circle and the action pill. Kept slightly translucent so a little of the
 * content behind the bar (e.g. a cover image) shows through, while staying legible.
 */
private const val TONAL_SURFACE_ALPHA = 0.85f

private fun Context.tonalSurfaceColor(): Int = ColorUtils.setAlphaComponent(
	getThemeColor(materialR.attr.colorSurfaceContainer),
	(255 * TONAL_SURFACE_ALPHA).roundToInt(),
)

/** Translucent circular tonal surface with a circular ripple — the navigation / close button fill. */
private fun Context.createCircleButtonBackground(): RippleDrawable = RippleDrawable(
	ColorStateList.valueOf(getThemeColor(android.R.attr.colorControlHighlight)),
	circle(tonalSurfaceColor()),
	circle(Color.WHITE),
)

/**
 * One segment of the connected action group: the tonal fill plus a ripple bounded to the same
 * rounded-rectangle outline. [roundLeft] / [roundRight] mark the group's outer edges (already
 * resolved for the layout direction); the remaining corners take the small inner radius.
 */
private fun Context.createSegmentBackground(
	metrics: TonalBarMetrics,
	roundLeft: Boolean,
	roundRight: Boolean,
): RippleDrawable {
	val left = if (roundLeft) metrics.outerCornerRadius else metrics.innerCornerRadius
	val right = if (roundRight) metrics.outerCornerRadius else metrics.innerCornerRadius
	// topLeft, topRight, bottomRight, bottomLeft — each as an x/y pair.
	val radii = floatArrayOf(left, left, right, right, right, right, left, left)
	return RippleDrawable(
		ColorStateList.valueOf(getThemeColor(android.R.attr.colorControlHighlight)),
		roundRect(tonalSurfaceColor(), radii),
		roundRect(Color.WHITE, radii),
	)
}

/** A transparent, fixed-width divider used purely as the gap between two segments. */
private fun spacerDrawable(widthPx: Int): Drawable = GradientDrawable().apply {
	shape = GradientDrawable.RECTANGLE
	setColor(Color.TRANSPARENT)
	setSize(widthPx, 1)
}

private fun roundRect(color: Int, radii: FloatArray): GradientDrawable = GradientDrawable().apply {
	shape = GradientDrawable.RECTANGLE
	cornerRadii = radii
	setColor(color)
}

private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
	shape = GradientDrawable.OVAL
	setColor(color)
}

// endregion
