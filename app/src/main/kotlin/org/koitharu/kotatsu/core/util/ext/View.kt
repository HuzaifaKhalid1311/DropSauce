package org.koitharu.kotatsu.core.util.ext

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Checkable
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.descendants
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.progressindicator.BaseProgressIndicator
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import kotlin.math.roundToInt
import org.koitharu.kotatsu.R
import com.google.android.material.R as materialR

/**
 * Sizes an empty-state view so its centered content lands on the middle of the display no matter how far
 * down the screen its host starts (plain app bar, Favourites' category tabs, Explore's header). Without
 * this every tab puts the mascot at a slightly different height.
 */
fun View.centerContentOnDisplay() {
	doOnLayout {
		val top = IntArray(2).also(::getLocationOnScreen)[1]
		val available = resources.displayMetrics.heightPixels - top * 2
		// Not enough room left to center in — let the content size itself instead of clipping it.
		val height = if (available >= resources.getDimensionPixelSize(R.dimen.empty_state_min_height)) {
			available
		} else {
			ViewGroup.LayoutParams.WRAP_CONTENT
		}
		if (layoutParams?.height != height) {
			updateLayoutParams { this.height = height }
		}
	}
}

fun View.hasGlobalPoint(x: Int, y: Int): Boolean {
	if (visibility != View.VISIBLE) {
		return false
	}
	val rect = Rect()
	getGlobalVisibleRect(rect)
	return rect.contains(x, y)
}

val ViewGroup.hasVisibleChildren: Boolean
	get() = children.any { it.isVisible }

/**
 * Shows/hides a stand-in for a [CollapsingToolbarLayout]'s expanded title while a search action
 * view is open. The Toolbar hides the CTL's internal title anchor during search, so the CTL stops
 * drawing the title even in the expanded (below-the-toolbar) position; this TextView takes its place
 * at the exact same spot. Must live in the CTL's parallax layer so it collapses away like the title.
 */
fun TextView.bindExpandedSearchTitle(
	ctl: CollapsingToolbarLayout,
	title: CharSequence?,
	expanded: Boolean,
) {
	if (expanded) {
		typeface = ctl.expandedTitleTypeface
		setTextSize(TypedValue.COMPLEX_UNIT_PX, ctl.expandedTitleTextSize)
		setTextColor(context.getThemeColor(materialR.attr.colorOnSurface))
		text = title
		updateLayoutParams<ViewGroup.MarginLayoutParams> {
			marginStart = ctl.expandedTitleMarginStart
			marginEnd = ctl.expandedTitleMarginEnd
			// The CTL aligns the expanded title's baseline expandedTitleMarginBottom above the layout
			// bottom; subtract the descent so this view's baseline lands on that same line.
			bottomMargin = (ctl.expandedTitleMarginBottom - paint.fontMetrics.descent)
				.roundToInt().coerceAtLeast(0)
		}
	}
	isVisible = expanded
}

fun View.measureHeight(): Int {
	val vh = height
	return if (vh == 0) {
		measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
		measuredHeight
	} else vh
}

fun View.measureWidth(): Int {
	val vw = width
	return if (vw == 0) {
		measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
		measuredWidth
	} else vw
}

inline fun ViewPager2.doOnPageChanged(crossinline callback: (Int) -> Unit) {
	registerOnPageChangeCallback(
		object : ViewPager2.OnPageChangeCallback() {

			override fun onPageSelected(position: Int) {
				super.onPageSelected(position)
				callback(position)
			}
		},
	)
}

val ViewPager2.recyclerView: RecyclerView?
	get() = children.firstNotNullOfOrNull { it as? RecyclerView }

fun ViewPager2.findCurrentViewHolder(): ViewHolder? {
	return recyclerView?.findViewHolderForAdapterPosition(currentItem)
}

fun FragmentManager.findCurrentPagerFragment(pager: ViewPager2): Fragment? {
	val currentId = pager.adapter?.getItemId(pager.currentItem) ?: pager.currentItem
	return findFragmentByTag("f$currentId")
}

fun View.resetTransformations() {
	alpha = 1f
	translationX = 0f
	translationY = 0f
	translationZ = 0f
	scaleX = 1f
	scaleY = 1f
	rotation = 0f
	rotationX = 0f
	rotationY = 0f
}

fun Slider.setValueRounded(newValue: Float) {
	val step = stepSize
	val roundedValue = if (step <= 0f) {
		newValue
	} else {
		(newValue / step).roundToInt() * step
	}
	value = roundedValue.coerceIn(valueFrom, valueTo)
}

fun RangeSlider.setValuesRounded(vararg newValues: Float) {
	val step = stepSize
	values = newValues.map { newValue ->
		if (step <= 0f) {
			newValue
		} else {
			(newValue / step).roundToInt() * step
		}.coerceIn(valueFrom, valueTo)
	}
}

fun RecyclerView.invalidateNestedItemDecorations() {
	descendants.filterIsInstance<RecyclerView>().forEach {
		it.invalidateItemDecorations()
	}
}

val View.parentView: ViewGroup?
	get() = parent as? ViewGroup

@Suppress("UnusedReceiverParameter")
fun View.measureDimension(desiredSize: Int, measureSpec: Int): Int {
	var result: Int
	val specMode = MeasureSpec.getMode(measureSpec)
	val specSize = MeasureSpec.getSize(measureSpec)
	if (specMode == MeasureSpec.EXACTLY) {
		result = specSize
	} else {
		result = desiredSize
		if (specMode == MeasureSpec.AT_MOST) {
			result = result.coerceAtMost(specSize)
		}
	}
	return result
}

fun <V> V.setChecked(checked: Boolean, animate: Boolean) where V : View, V : Checkable {
	val skipAnimation = !animate && checked != isChecked
	isChecked = checked
	if (skipAnimation) {
		jumpDrawablesToCurrentState()
	}
}

var View.isRtl: Boolean
	get() = layoutDirection == View.LAYOUT_DIRECTION_RTL
	set(value) {
		layoutDirection = if (value) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
	}

fun TabLayout.setTabsEnabled(enabled: Boolean) {
	for (i in 0 until tabCount) {
		getTabAt(i)?.view?.isEnabled = enabled
	}
}

fun BaseProgressIndicator<*>.showOrHide(value: Boolean) {
	if (value) {
		show()
	} else {
		hide()
	}
}

fun LoadingIndicator.showOrHide(value: Boolean) {
	if (value) {
		show()
	} else {
		hide()
	}
}

fun View.setTooltipCompat(tooltip: CharSequence?) {
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
		tooltipText = tooltip
	} else if (!isLongClickable) { // don't use TooltipCompat if has a LongClickListener
		TooltipCompat.setTooltipText(this, tooltip)
	}
}

fun View.setTooltipCompat(@StringRes tooltipResId: Int) = setTooltipCompat(context.getString(tooltipResId))

val Toolbar.menuView: ActionMenuView?
	get() {
		menu // to call ensureMenu()
		return children.firstNotNullOfOrNull { it as? ActionMenuView }
	}

fun MaterialButton.setProgressIcon() {
	val progressDrawable = CircularProgressDrawable(context)
	progressDrawable.strokeWidth = resources.resolveDp(2f)
	progressDrawable.setColorSchemeColors(currentTextColor)
	progressDrawable.setTintList(textColors)
	icon = progressDrawable
	progressDrawable.start()
}

fun Chip.setProgressIcon() {
	val progressDrawable = CircularProgressDrawable(context)
	progressDrawable.strokeWidth = resources.resolveDp(2f)
	progressDrawable.setColorSchemeColors(currentTextColor)
	chipIcon = progressDrawable
	progressDrawable.start()
}

fun View.setContentDescriptionAndTooltip(@StringRes resId: Int) {
	val text = resources.getString(resId)
	contentDescription = text
	setTooltipCompat(text)
}

fun View.getWindowBounds(): Rect {
	val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
	return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
		wm.currentWindowMetrics.bounds
	} else {
		val size = Point()
		@Suppress("DEPRECATION")
		display.getSize(size)
		Rect(0, 0, size.x, size.y)
	}
}

fun View.isOnScreen(): Boolean {
	if (!isShown) {
		return false
	}
	val actualPosition = Rect()
	getGlobalVisibleRect(actualPosition)
	return actualPosition.intersect(getWindowBounds())
}
