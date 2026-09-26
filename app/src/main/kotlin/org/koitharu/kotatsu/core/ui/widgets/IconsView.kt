package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.history.ui.util.ReadingProgressView
import kotlin.math.roundToInt

/**
 * The frosted "saved / favourite" pill shown over manga covers. Always exactly as tall as the
 * reading-progress pill (including its compact size), with the icons scaled to fit.
 */
class IconsView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

	private var iconSpacing = 0
	private var isSmall = false
	private var iconSize = 0
	private val savedTint = ContextCompat.getColor(context, R.color.list_badge_saved)
	private val favouriteTint = ContextCompat.getColor(context, R.color.list_badge_favourite)

	init {
		context.withStyledAttributes(attrs, R.styleable.IconsView) {
			iconSpacing = getDimensionPixelOffset(R.styleable.IconsView_iconSpacing, iconSpacing)
		}
		applySize()
	}

	fun setMangaBadges(isSaved: Boolean, isFavorite: Boolean) {
		repeat(childCount) { i -> getChildAt(i).isVisible = false }
		if (isFavorite) addIcon(R.drawable.ic_heart, favouriteTint)
		if (isSaved) addIcon(R.drawable.ic_storage_filled, savedTint)
		isVisible = isSaved || isFavorite
	}

	/** Mirrors [ReadingProgressView.setSmall] for compact grid cells. */
	fun setSmall(small: Boolean) {
		if (small != isSmall) {
			isSmall = small
			applySize()
		}
	}

	private fun applySize() {
		val pillHeight = ReadingProgressView.pillHeight(resources, isSmall)
		iconSize = (pillHeight * ICON_RATIO).roundToInt()
		val vPadding = (pillHeight - iconSize) / 2
		setPaddingRelative(paddingStart, vPadding, paddingEnd, pillHeight - iconSize - vPadding)
		repeat(childCount) { i ->
			getChildAt(i).updateLayoutParams {
				width = iconSize
				height = iconSize
			}
		}
	}

	private fun addIcon(@DrawableRes resId: Int, @ColorInt tint: Int) {
		val imageView = getNextImageView()
		imageView.setImageResource(resId)
		imageView.imageTintList = ColorStateList.valueOf(tint)
		imageView.isVisible = true
	}

	private fun getNextImageView(): ImageView {
		repeat(childCount) { i ->
			val child = getChildAt(i)
			if (child is ImageView && !child.isVisible) {
				return child
			}
		}
		return addImageView()
	}

	private fun addImageView() = ImageView(context).also {
		it.scaleType = ImageView.ScaleType.FIT_CENTER
		val lp = LayoutParams(iconSize, iconSize)
		if (isNotEmpty()) {
			lp.marginStart = iconSpacing
		}
		addView(it, lp)
	}

	private companion object {

		const val ICON_RATIO = 0.68f
	}
}
