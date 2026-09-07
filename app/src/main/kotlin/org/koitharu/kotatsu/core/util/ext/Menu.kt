package org.koitharu.kotatsu.core.util.ext

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.view.Menu
import android.view.MenuItem
import org.koitharu.kotatsu.R
import java.lang.reflect.Method
import kotlin.math.min

fun Menu.setOptionalIconsVisibleCompat(isVisible: Boolean) {
	findOptionalIconsMethod()?.let { method ->
		runCatching { method.invoke(this, isVisible) }
	}
}

fun Menu.adjustPopupMenuIcons(resources: Resources, shouldSkip: (MenuItem) -> Boolean = { false }) {
	val iconSize = resources.getDimensionPixelSize(R.dimen.menu_popup_item_icon_size)
	for (index in 0 until size()) {
		val item = getItem(index)
		item.icon?.let { icon ->
			if (icon !is PopupMenuIconDrawable && !shouldSkip(item)) {
				item.icon = PopupMenuIconDrawable(icon.mutate(), iconSize)
			}
		}
		item.subMenu?.adjustPopupMenuIcons(resources, shouldSkip)
	}
}

@SuppressLint("PrivateApi")
private fun Menu.findOptionalIconsMethod(): Method? {
	var currentClass: Class<*>? = javaClass
	while (currentClass != null) {
		val method = runCatching {
			currentClass.getDeclaredMethod("setOptionalIconsVisible", Boolean::class.javaPrimitiveType)
		}.getOrNull()
		if (method != null) {
			method.isAccessible = true
			return method
		}
		currentClass = currentClass.superclass
	}
	return null
}

/**
 * Renders [source] at a fixed square [iconSize], centered in whatever bounds it is given, so every
 * menu icon reads at the same size no matter what its own drawable declares. It is a transparent
 * wrapper: state, alpha, colour filter and **tint** are all forwarded, so a themed icon still tints
 * normally — this drawable also ends up on toolbar action items, where a swallowed tint would leave
 * a single icon in the bar off-colour.
 */
private class PopupMenuIconDrawable(
	private val source: Drawable,
	private val iconSize: Int,
) : Drawable() {

	override fun draw(canvas: Canvas) {
		val bounds = bounds
		val size = min(iconSize, min(bounds.width(), bounds.height()))
		val left = bounds.left + (bounds.width() - size) / 2
		val top = bounds.top + (bounds.height() - size) / 2
		source.setBounds(left, top, left + size, top + size)
		source.draw(canvas)
	}

	override fun getIntrinsicWidth(): Int = iconSize

	override fun getIntrinsicHeight(): Int = iconSize

	override fun setTintList(tint: ColorStateList?) {
		source.setTintList(tint)
	}

	override fun setTintMode(tintMode: PorterDuff.Mode?) {
		source.setTintMode(tintMode)
	}

	override fun setAlpha(alpha: Int) {
		source.alpha = alpha
	}

	override fun setColorFilter(colorFilter: ColorFilter?) {
		source.colorFilter = colorFilter
	}

	@Deprecated("Deprecated in Java")
	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

	override fun isStateful(): Boolean = source.isStateful

	override fun onStateChange(state: IntArray): Boolean {
		return source.setState(state)
	}

	override fun onLevelChange(level: Int): Boolean {
		return source.setLevel(level)
	}
}
